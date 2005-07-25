/*
 * ====================================================================
 * This software is subject to the terms of the Common Public License
 * Agreement, available at the following URL:
 *   http://www.opensource.org/licenses/cpl.html .
 * Copyright (C) 2003-2004 TONBELLER AG.
 * All Rights Reserved.
 * You must accept the terms of that agreement to use this software.
 * ====================================================================
 *
 * 
 */
package com.tonbeller.jpivot.xmla;

import java.io.StringWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.soap.Detail;
import javax.xml.soap.DetailEntry;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.Name;
import javax.xml.soap.Node;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPConnection;
import javax.xml.soap.SOAPConnectionFactory;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPEnvelope;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFault;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPPart;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;

import com.tonbeller.jpivot.olap.model.OlapDiscoverer;
import com.tonbeller.jpivot.olap.model.OlapException;
import com.tonbeller.jpivot.olap.model.OlapItem;
import com.tonbeller.jpivot.olap.model.QueryResultHandler;

/**
 * Handling XMLA SOAP calls
 */
public class XMLA_SOAP implements OlapDiscoverer {

  static final String MDD_URI = "urn:schemas-microsoft-com:xml-analysis:mddataset";
  static final String ROWS_URI = "urn:schemas-microsoft-com:xml-analysis:rowset";
  static final String XMLA_URI = "urn:schemas-microsoft-com:xml-analysis";
  static final String XSI_URI = "http://www.w3.org/2001/XMLSchema-instance";

  static Logger logger = Logger.getLogger(XMLA_SOAP.class);

  private SOAPConnectionFactory scf = null;
  private MessageFactory mf = null;

  private int provider = 0;

  private String uri;
  private URL url;
  private String dataSource;

  private String user;
  private String password;

  interface Rowhandler {
    void handleRow(SOAPElement eRow, SOAPEnvelope envelope);
  }

  /**
   * c'tor
   * set URI, password, user and create URL
   *  no datasource, no provider, will be determined by discover datasource
   * @param uri
   * @param user
   * @param password  
   */
  public XMLA_SOAP(String uri, String user, String password) throws OlapException {
    init(uri, user, password);

    Map resMap;
    resMap = this.discoverDS();
    // discoverDS did set the provider, if it was unknown
    if (resMap.size() > 0) {
      if (provider == OlapDiscoverer.PROVIDER_MICROSOFT) {
        dataSource = (String) resMap.get("DataSourceInfo");
      } else {
        // supposed to be SAP
        String dstr = (String) resMap.get("DataSourceDescription");
        String pstr = (String) resMap.get("ProviderName");
        if (dstr == null || pstr == null)
          throw new OlapException("Unexpected result from Discover Datasource");
        dataSource = "Provider=" + pstr + ";Data Source=" + dstr;
        if (dataSource.toUpperCase().startsWith("PROVIDER=MS"))
          provider = OlapDiscoverer.PROVIDER_MICROSOFT;
        else
          provider = OlapDiscoverer.PROVIDER_SAP;
      }
    } else {
      throw new OlapException("No result from Discover Datasource");
    }
  }

  /**
   * c'tor
   * set URI, password, user and create URL
   *  datasource given, provider will be determined from datasource
   * @param uri
   * @param user
   * @param password  
   * @param dataSource
   */
  public XMLA_SOAP(String uri, String user, String password, String dataSource)
      throws OlapException {
    init(uri, user, password);

    this.dataSource = dataSource;
    if (dataSource.toUpperCase().startsWith("PROVIDER=SAP"))
      provider = OlapDiscoverer.PROVIDER_SAP;
    else
      provider = OlapDiscoverer.PROVIDER_MICROSOFT;

  }

  /**
   * c'tor
   * set URI, password, user and create URL
   *  provider given, datasource will be determined by discover datasource
   * @param uri
   * @param user
   * @param password  
   * @param provider
   */
  public XMLA_SOAP(String uri, String user, String password, int provider) throws OlapException {
    init(uri, user, password);
    Map dsMap = discoverDS();
    if (dsMap.size() > 0) {
      if (provider == PROVIDER_SAP) {
        String dsinfo = (String) dsMap.get("DataSourceDescription");
        String provName = (String) dsMap.get("ProviderName");
        dataSource = "Provider=" + provName + ";Data Source=" + dsinfo;
      } else {
        dataSource = (String) dsMap.get("DataSourceInfo");
      }
    }
  }

  /*
   * init
   */
  private void init(String uri, String user, String password) throws OlapException {
    try {
      scf = SOAPConnectionFactory.newInstance();
      mf = MessageFactory.newInstance();
    } catch (UnsupportedOperationException e) {
      throw new OlapException(e);
    } catch (SOAPException e) {
      throw new OlapException(e);
    }

    this.uri = uri;
    this.user = user;
    this.password = password;
    try {
      url = new URL(uri);
    } catch (MalformedURLException e1) {
      throw new OlapException(e1);
    }

    if (user != null && user.length() > 0) {
      String newUri = url.getProtocol() + "://" + user;
      if (password != null && password.length() > 0) {
        newUri += ":" + password;
      }
      newUri += "@" + url.getHost() + ":" + url.getPort() + url.getPath();

      try {
        url = new URL(newUri);
      } catch (MalformedURLException e2) {
        throw new OlapException(e2);
      }
    }
  }

  /**
   * retrieve catalogs in data source
   * @return List of OlapItems for the catalogs
   * @see DataSourceBrowser
   */
  public List discoverCat() throws OlapException {
    final List cats = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap(); // empty

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_CATALOG);
        cats.add(oi);
        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;
          String lname = e.getElementName().getLocalName();
          if (lname.equals("CATALOG_NAME"))
            oi.setName(e.getValue());
          else
            oi.setProperty(lname, e.getValue());
        }
      }
    };

    discover("DBSCHEMA_CATALOGS", url, rHash, pHash, rh);

    return cats;
  }

  /**
   * retrieve datasource properties
   * @return List of OlapItems for the datasource properties
   * @see DataSourceBrowser
   */
  public List discoverDSProps() throws OlapException {
    final List props = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap(); // empty

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_PROPERTY);
        props.add(oi);
        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;
          String lname = e.getElementName().getLocalName();
          if ( lname.equals("PropertyName") )
            oi.setName( e.getValue());
          oi.setProperty(lname, e.getValue());
        }
      }
    };

    discover("DISCOVER_PROPERTIES", url, rHash, pHash, rh);

    return props;
  }

  /**
   * retrieve cubes in data source
   * @return List of OlapItems for the cubes
   * @see DataSourceBrowser 
   */
  public List discoverCube(String cat) throws OlapException {
    final List cubes = new ArrayList();
    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Content", "SchemaData");
    pHash.put("Catalog", cat); // needed, or else can only discover first catalog's cubes

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_CUBE);
        cubes.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;
          String lname = e.getElementName().getLocalName();
          if (lname.equals("CUBE_NAME"))
            oi.setName(e.getValue());
          else
            oi.setProperty(lname, e.getValue());
        }
      }

    };
    discover("MDSCHEMA_CUBES", url, rHash, pHash, rh);
    return cubes;
  }

  /**
   * retrieve dimensions in data source
   * @return List of OlapItems for the dimensions
   * @see DataSourceBrowser 
   */
  public List discoverDim(String cat, String cube) throws OlapException {
    final List dims = new ArrayList();
    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {
        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_DIMENSION);
        dims.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("DIMENSION_UNIQUE_NAME")) {
            oi.setUniqueName(e.getValue());
          } else if (lname.equals("DIMENSION_CAPTION")) {
            oi.setCaption(e.getValue());
          } else if (lname.equals("DIMENSION_NAME")) {
            oi.setName(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }

        }
      }

    };

    discover("MDSCHEMA_DIMENSIONS", url, rHash, pHash, rh);
    return dims;
  }

  /**
   * retrieve hierarchies in data source
   * @return List of OlapItems for the hierarchies
   * @see DataSourceBrowser 
   */
  public List discoverHier(String cat, String cube, String dimension) throws OlapException {
    final List hiers = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);
    if (dimension != null)
      rHash.put("DIMENSION_UNIQUE_NAME", dimension);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {
        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_HIERARCHY);
        hiers.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;
          String lname = e.getElementName().getLocalName();
          if (lname.equals("HIERARCHY_UNIQUE_NAME")) {
            oi.setUniqueName(e.getValue());
          } else if (lname.equals("HIERARCHY_CAPTION")) {
            oi.setCaption(e.getValue());
          } else if (lname.equals("HIERARCHY_NAME")) {
            oi.setName(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }
      }

    };

    discover("MDSCHEMA_HIERARCHIES", url, rHash, pHash, rh);
    return hiers;
  }

  /**
   * retrieve levels in data source
   * @return List of OlapItems for the levels
   * @see DataSourceBrowser 
   */
  public List discoverLev(String cat, String cube, String dimension, String hier)
      throws OlapException {

    final List levels = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);
    if (dimension != null)
      rHash.put("DIMENSION_UNIQUE_NAME", dimension);
    if (hier != null)
      rHash.put("HIERARCHY_UNIQUE_NAME", dimension);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {
      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_LEVEL);
        levels.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("LEVEL_UNIQUE_NAME")) {
            oi.setUniqueName(e.getValue());
          } else if (lname.equals("LEVEL_CAPTION")) {
            oi.setCaption(e.getValue());
          } else if (lname.equals("LEVEL_NAME")) {
            oi.setName(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }
      }

    };

    discover("MDSCHEMA_LEVELS", url, rHash, pHash, rh);
    return levels;
  }

  /**
   * retrieve members in data source
   * @return List of OlapItems for the members
   * @see DataSourceBrowser
   */
  public List discoverMem(String cat, String cube, String dimension, String hierarchy, String level)
      throws OlapException {
    final List mems = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);
    if (dimension != null)
      rHash.put("DIMENSION_UNIQUE_NAME", dimension);
    if (hierarchy != null)
      rHash.put("HIERARCHY_UNIQUE_NAME", hierarchy);
    if (level != null)
      rHash.put("LEVEL_UNIQUE_NAME", level);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {

      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_MEMBER);
        mems.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("MEMBER_UNIQUE_NAME")) {
            oi.setUniqueName(e.getValue());
          } else if (lname.equals("MEMBER_CAPTION")) {
            oi.setCaption(e.getValue());
          } else if (lname.equals("MEMBER_NAME")) {
            oi.setName(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }
      }

    };

    discover("MDSCHEMA_MEMBERS", url, rHash, pHash, rh);

    return mems;
  }

  /**
   * retrieve member tree in data source for given catalog, cube, member
   * @param cat name of catalog 
   * @param cube name of cube  
   * @param member unique name of member
   * @param treeop bit combination according to TREEOP specification
   *               MDTREEOP_CHILDREN = 1
   *               MDTREEOP_SIBLINGS = 2
   *               MDTREEOP_PARENT = 4
   *               MDTREEOP_SELF = 8
   *               MDTREEOP_DESCENDANTS = 16
   *               MDTREEOP_ANCESTORS = 32
   * @return List of OlapItems for the members
   * @throws OlapException
   * @see com.tonbeller.jpivot.olap.model.OlapDiscoverer#discoverMemTree
   */

  public List discoverMemTree(String cat, String cube, String member, int treeop)
      throws OlapException {
    final List mems = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);
    rHash.put("MEMBER_UNIQUE_NAME", member);
    rHash.put("TREE_OP", String.valueOf(treeop));

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {

      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_MEMBER);
        mems.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("MEMBER_UNIQUE_NAME")) {
            oi.setUniqueName(e.getValue());
          } else if (lname.equals("MEMBER_CAPTION")) {
            oi.setCaption(e.getValue());
          } else if (lname.equals("MEMBER_NAME")) {
            oi.setName(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }
      }

    };

    discover("MDSCHEMA_MEMBERS", url, rHash, pHash, rh);
    return mems;
  }

  /**
   * retrieve data source properties
   * @return Map of key/value strings 
   * @see DataSourceBrowser
   */
  public Map discoverDS() throws OlapException {
    // Microsoft wants restrictions
    HashMap rHash = new HashMap();

    HashMap pHash = new HashMap();
    pHash.put("Content", "Data");
    final Map resultMap = new HashMap();
    Rowhandler rh = new Rowhandler() {

      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {

        /*
         <row><DataSourceName>SAP_BW</DataSourceName>
         <DataSourceDescription>SAP BW Release 3.0A XML f. Analysis Service</DataSourceDescription>
         <URL>http://155.56.49.46:83/SAP/BW/XML/SOAP/XMLA</URL>
         <DataSourceInfo>default</DataSourceInfo>
         <ProviderName>SAP BW</ProviderName>
         <ProviderType>MDP</ProviderType>
         <AuthenticationMode>Integrated</AuthenticationMode></row>
         */
        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue; //bypass text nodes
          SOAPElement e = (SOAPElement) o;
          String name = e.getElementName().getLocalName();
          String value = e.getValue();
          resultMap.put(name, value);
        }
      }
    };

    discover("DISCOVER_DATASOURCES", url, rHash, pHash, rh);
    return resultMap;

  }

  /**
   * retrieve member properties in data source for given catalog, cube, dimension, hierarchy, level
   * @param cat name of catalog 
   * @param cube name of cube  
   * @param dimension unique name of dimension
   * @param hierarchy unique name of hierarchy   
   * @param level unique name of level
   * @return List of OlapItems for the members
   * @throws OlapException
   * @see com.tonbeller.jpivot.olap.model.OlapDiscoverer#discoverProp 
   */
  public List discoverProp(String cat, String cube, String dimension, String hierarchy, String level)
      throws OlapException {
    final List props = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);
    if (dimension != null)
      rHash.put("DIMENSION_UNIQUE_NAME", dimension);
    if (hierarchy != null)
      rHash.put("HIERARCHY_UNIQUE_NAME", hierarchy);
    if (level != null)
      rHash.put("LEVEL_UNIQUE_NAME", level);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {

      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {
        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_PROPERTY);
        props.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("PROPERTY_NAME")) {
            oi.setName(e.getValue());
          } else if (lname.equals("PROPERTY_CAPTION")) {
            oi.setCaption(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }

      }
    };

    discover("MDSCHEMA_PROPERTIES", url, rHash, pHash, rh);

    return props;
  }

  /**
   * retrieve SAP variables for given catalog, cube
   * @param cat name of catalog 
   * @param cube name of cube  
   * @return List of OlapItems for the members
   * @throws OlapException
   * @see com.tonbeller.jpivot.olap.model.OlapDiscoverer#discoverProp 
   */
  public List discoverSapVar(String cat, String cube) throws OlapException {
    final List props = new ArrayList();

    // restrictions
    HashMap rHash = new HashMap();
    rHash.put("CATALOG_NAME", cat);
    rHash.put("CUBE_NAME", cube);

    // properties   
    HashMap pHash = new HashMap();
    pHash.put("DataSourceInfo", dataSource);
    pHash.put("Catalog", cat); // neccessary ???
    pHash.put("Content", "SchemaData");

    Rowhandler rh = new Rowhandler() {

      public void handleRow(SOAPElement eRow, SOAPEnvelope envelope) {
        XMLA_OlapItem oi = new XMLA_OlapItem(OlapItem.TYPE_PROPERTY);
        props.add(oi);

        Iterator it = eRow.getChildElements();
        while (it.hasNext()) {
          Object o = it.next();
          if (!(o instanceof SOAPElement))
            continue;
          SOAPElement e = (SOAPElement) o;

          String lname = e.getElementName().getLocalName();
          if (lname.equals("VARIABLE_NAME")) {
            // ??? probably not supported
            oi.setName(e.getValue());
          } else if (lname.equals("VARIABLE_CAPTION")) { // ?? probably not supported
            oi.setCaption(e.getValue());
          } else {
            oi.setProperty(lname, e.getValue());
          }
        }

      }
    };

    discover("SAP_VARIABLES", url, rHash, pHash, rh);

    return props;
  }

  /**
   * Execute query
   * @param query - MDX to be executed
   * @param catalog
   * @param handler Callback handler
   * @throws OlapException
   */
  public void executeQuery(String query, String catalog, QueryResultHandler handler)
      throws OlapException {

    SOAPConnection connection = null;
    SOAPMessage reply = null;

    try {
      connection = scf.createConnection();
      SOAPMessage msg = mf.createMessage();

      MimeHeaders mh = msg.getMimeHeaders();
      mh.setHeader("SOAPAction", "\"urn:schemas-microsoft-com:xml-analysis:Execute\"");

      SOAPPart soapPart = msg.getSOAPPart();
      SOAPEnvelope envelope = soapPart.getEnvelope();
      SOAPBody body = envelope.getBody();
      Name nEx = envelope.createName("Execute", "", XMLA_URI);

      SOAPElement eEx = body.addChildElement(nEx);

      // add the parameters

      // COMMAND parameter
      // <Command>
      // <Statement>select [Measures].members on Columns from Sales</Statement>
      // </Command>
      Name nCom = envelope.createName("Command");
      SOAPElement eCommand = eEx.addChildElement(nCom);
      Name nSta = envelope.createName("Statement");
      SOAPElement eStatement = eCommand.addChildElement(nSta);
      eStatement.addTextNode(query);

      // <Properties>
      // <PropertyList>
      //    <DataSourceInfo>Provider=MSOLAP;Data Source=local</DataSourceInfo>
      //    <Catalog>Foodmart 2000</Catalog>
      //    <Format>Multidimensional</Format>
      //    <AxisFormat>TupleFormat</AxisFormat> oder "ClusterFormat"
      // </PropertyList>
      // </Properties>
      Map paraList = new HashMap();
      paraList.put("DataSourceInfo", dataSource);
      paraList.put("Catalog", catalog);
      paraList.put("Format", "Multidimensional");
      paraList.put("AxisFormat", "TupleFormat");
      addParameterList(envelope, eEx, "Properties", "PropertyList", paraList);
      msg.saveChanges();

      // run the call
      reply = connection.call(msg, url);
      if (logger.isDebugEnabled()) {
        logger.debug("Reply from Execute");
        //reply.getSOAPPart().getContent().
        logSoapMsg(reply);
      }

      // error check
      errorCheck(reply);
      // process the reply

      SOAPElement eRoot = findExecRoot(reply);

      // determine axes from <OlapInfo><AxesInfo><AxisInfo>
      Name name = envelope.createName("OlapInfo", "", MDD_URI);
      SOAPElement eOlapInfo = selectSingleNode(eRoot, name);
      if (eOlapInfo == null)
        throw new OlapException("Excecute result has no eOlapInfo element");

      name = envelope.createName("AxesInfo", "", MDD_URI);
      SOAPElement eAxesInfo = selectSingleNode(eOlapInfo, name);
      if (eAxesInfo == null)
        throw new OlapException("Excecute result has no AxesInfo element");

      name = envelope.createName("AxisInfo", "", MDD_URI);
      Iterator itAxisInfo = eAxesInfo.getChildElements(name);

      int iOrdinal = 0;
      AxisInfoLoop: while (itAxisInfo.hasNext()) {
        SOAPElement eAxisInfo = (SOAPElement) itAxisInfo.next();

        name = envelope.createName("name");
        String axisName = eAxisInfo.getAttributeValue(name);
        int axisOrdinal;
        if (axisName.equals("SlicerAxis"))
          axisOrdinal = -1;
        else
          axisOrdinal = iOrdinal++;

        handler.handleAxisInfo(axisName, axisOrdinal);

        // retrieve the hierarchies by <HierarchyInfo>
        name = envelope.createName("HierarchyInfo", "", MDD_URI);
        Iterator itHierInfo = eAxisInfo.getChildElements(name);

        int hierNumber = 0;
        HierInfoLoop: while (itHierInfo.hasNext()) {
          SOAPElement eHierInfo = (SOAPElement) itHierInfo.next();
          name = envelope.createName("name");
          String hierName = eHierInfo.getAttributeValue(name);
          handler.handleHierInfo(hierName, axisOrdinal, hierNumber++);
        } // HierInfoLoop

      } // AxisInfoLoop

      // for each axis, get the positions (tuples)
      name = envelope.createName("Axes", "", MDD_URI);
      SOAPElement eAxes = selectSingleNode(eRoot, name);
      if (eAxes == null)
        throw new OlapException("Excecute result has no Axes element");

      name = envelope.createName("Axis", "", MDD_URI);
      Iterator itAxis = eAxes.getChildElements(name);

      AxisLoop: for (iOrdinal = 0; itAxis.hasNext();) {
        SOAPElement eAxis = (SOAPElement) itAxis.next();
        name = envelope.createName("name");
        String axisName = eAxis.getAttributeValue(name);
        int axisOrdinal;
        if (axisName.equals("SlicerAxis"))
          axisOrdinal = -1;
        else
          axisOrdinal = iOrdinal++;

        handler.handleAxis(axisName, axisOrdinal);

        name = envelope.createName("Tuples", "", MDD_URI);
        SOAPElement eTuples = selectSingleNode(eAxis, name);
        if (eTuples == null)
          continue AxisLoop; // what else?

        name = envelope.createName("Tuple", "", MDD_URI);
        Iterator itTuple = eTuples.getChildElements(name);

        // loop over tuples
        int positionOrdinal = 0;
        TupleLoop: while (itTuple.hasNext()) {
          SOAPElement eTuple = (SOAPElement) itTuple.next();
          handler.handleTuple(axisOrdinal, positionOrdinal);

          // loop over members
          //XMLA_Member[] posMembers = new XMLA_Member[axis.getNHier()];

          int index = 0;
          name = envelope.createName("Member", "", MDD_URI);
          Iterator itMember = eTuple.getChildElements(name);
          MemberLoop: while (itMember.hasNext()) {
            SOAPElement eMem = (SOAPElement) itMember.next();
            // loop over children nodes
            String uName = null;
            String caption = null;
            String levUname = null;
            String displayInfo = null;
            Iterator it = eMem.getChildElements();
            Map otherProps = new HashMap();
            InnerLoop: while (it.hasNext()) {
              Node n = (Node) it.next();
              if (!(n instanceof SOAPElement))
                continue InnerLoop;
              SOAPElement el = (SOAPElement) n;
              String enam = el.getElementName().getLocalName();
              if (enam.equals("UName"))
                uName = el.getValue();
              else if (enam.equals("Caption"))
                caption = el.getValue();
              else if (enam.equals("LName"))
                levUname = el.getValue();
              else if (enam.equals("DisplayInfo"))
                displayInfo = el.getValue();
              else
                otherProps.put(enam, el.getValue());
            }
            handler.handleMember(uName, caption, levUname, displayInfo, otherProps, axisOrdinal,
                positionOrdinal, index);
            ++index;
          } //MemberLoop

          ++positionOrdinal;
        } // TupleLoop
      } // AxisLoop

      // loop over cells in result set
      name = envelope.createName("CellData", "", MDD_URI);
      SOAPElement eCellData = selectSingleNode(eRoot, name);
      handler.handleCellData(); // start cell loop
      name = envelope.createName("Cell", "", MDD_URI);
      Iterator itSoapCell = eCellData.getChildElements(name);
      CellLoop: while (itSoapCell.hasNext()) {
        SOAPElement eCell = (SOAPElement) itSoapCell.next();
        name = envelope.createName("CellOrdinal", "", "");
        String cellOrdinal = eCell.getAttributeValue(name);
        int ordinal = Integer.parseInt(cellOrdinal);
        name = envelope.createName("Value", "", MDD_URI);
        SOAPElement eValue = selectSingleNode(eCell, name);
        Object value = null;
        if (eValue != null) {
          name = envelope.createName("type", "xsi", XSI_URI);
          String type = eValue.getAttributeValue(name);
          /*
           if ( type == null) {
           // probably Error
           String eCode = "unknown";
           String eDescription = "unknown";
           name = envelope.createName("Error", "", MDD_URI);
           SOAPElement eError = selectSingleNode(eValue, name);
           if (eError != null) {
           name = envelope.createName("ErrorCode", "", MDD_URI);
           SOAPElement eErrorCode = selectSingleNode(eError, name);
           if (eErrorCode != null) {
           eCode =  eErrorCode.getValue();
           }
           name = envelope.createName("Description", "", MDD_URI);
           SOAPElement eErrorDesc = selectSingleNode(eError, name);
           if (eErrorDesc != null) {
           eDescription =  eErrorDesc.getValue();
           }
           }
           throw new OlapException("Error reading Cell: Error Code = " + eCode +
           " Description = " + eDescription);
           }
           */
          if ("xsd:int".equals(type)) {
            // value = new Integer(eValue.getValue()); //EGO crash too long
            value = new Long(eValue.getValue());
          } else if ("xsd:double".equals(type)) {
            value = new Double(eValue.getValue());
          } else {
            value = eValue.getValue();
          }
        }
        name = envelope.createName("FmtValue", "", MDD_URI);
        SOAPElement eFmtValue = selectSingleNode(eCell, name);
        String fmtValue;
        if (eFmtValue != null)
          fmtValue = eFmtValue.getValue();
        else
          fmtValue = "";

        name = envelope.createName("FontSize", "", MDD_URI);
        SOAPElement eFontSize = selectSingleNode(eCell, name);
        String fontSize = null;
        if (eFontSize != null)
          fontSize = eFontSize.getValue();

        handler.handleCell(ordinal, value, fmtValue, fontSize);

      } // CellLoop

    } catch (SOAPException se) {
      throw new OlapException(se);
    } finally {
      if (connection != null)
        try {
          connection.close();
        } catch (SOAPException e) {
          // log and ignore 
          logger.error("?", e);
        }
    }

  }

  // dsf
  public void executeDrillQuery(String query, String catalog, QueryResultHandler handler)
  throws OlapException {

  SOAPConnection connection = null;
  SOAPMessage reply = null;

  try {
    connection = scf.createConnection();
    SOAPMessage msg = mf.createMessage();

    MimeHeaders mh = msg.getMimeHeaders();
    mh.setHeader("SOAPAction", "\"urn:schemas-microsoft-com:xml-analysis:Execute\"");

    SOAPPart soapPart = msg.getSOAPPart();
    SOAPEnvelope envelope = soapPart.getEnvelope();
    SOAPBody body = envelope.getBody();
    Name nEx = envelope.createName("Execute", "", XMLA_URI);

    SOAPElement eEx = body.addChildElement(nEx);

    // add the parameters

    // COMMAND parameter
    // <Command>
    // <Statement>select [Measures].members on Columns from Sales</Statement>
    // </Command>
    Name nCom = envelope.createName("Command");
    SOAPElement eCommand = eEx.addChildElement(nCom);
    Name nSta = envelope.createName("Statement");
    SOAPElement eStatement = eCommand.addChildElement(nSta);
    eStatement.addTextNode(query);

    Map paraList = new HashMap();
    paraList.put("DataSourceInfo", dataSource);
    paraList.put("Catalog", catalog);
    //dsf : Note the use of tabular format instead of multidimensional. This is crucial 
    // otherwise the drillthrough will fail
    paraList.put("Format", "Tabular");
    addParameterList(envelope, eEx, "Properties", "PropertyList", paraList);
    msg.saveChanges();
  
    reply = connection.call(msg, url);
    if (logger.isDebugEnabled()) {
      logger.debug("Reply from Execute");
      //reply.getSOAPPart().getContent().
      logSoapMsg(reply);
    } 

    // error check  
    errorCheck(reply);
    // process the reply

    SOAPElement eRoot = findDrillExecRoot(reply);
   
    // first the column headers
    // Note the use of Hashmap for the column names. This is because 
    // the reponse message can return variable number of data columns depending on whether
    // the column data value is null. The hash map is used to store the column name and the
    // column position so that when we do the rendering we can map the data to its appropriate column header.

    Name name = envelope.createName("row", "", ROWS_URI);
    SOAPElement columnHeader = selectSingleNode(eRoot, name);
    if (columnHeader == null)
      throw new OlapException("Excecute result has no rows element");
    
	Map colNames = new HashMap();
    Iterator columnHeaderIt = columnHeader.getChildElements();
    int colIdx = 0;
    RowHeadLoop : while (columnHeaderIt.hasNext()) {
  		Object columnHeaderObj  = columnHeaderIt.next();
  		if (columnHeaderObj instanceof SOAPElement) {
  			String colName = ((SOAPElement)columnHeaderObj).getElementName().getLocalName();
  			colNames.put(colName,new Integer(colIdx));
  			colIdx++;
  		}
    }		

//    handler.setDrillHeader(colNames); 
    

    // extract the data for each row
    
    ArrayList drillRows = new ArrayList();
    name = envelope.createName("row", "", ROWS_URI);
    Iterator rowIt = eRoot.getChildElements(name); 
    while  (rowIt.hasNext()) {
    	SOAPElement rowElement = (SOAPElement) rowIt.next();

    	// process the column in each row
    	Iterator rowDataIt = rowElement.getChildElements();
    	javax.xml.soap.Text child  = (javax.xml.soap.Text)rowDataIt.next(); 
		SOAPElement columnElement = child.getParentElement();
 		Iterator columnIterator = columnElement.getChildElements();

 		Map dataRow = new HashMap();
 		while (columnIterator.hasNext()) {
 		Object colObject = columnIterator.next();
 		if (colObject instanceof SOAPElement) {
 			String colName = ((SOAPElement)colObject).getElementName().getLocalName();
 			if (!colNames.containsKey(colName)) {
 				colNames.put(colName,new Integer(colIdx));
 				colIdx++;
 			}
 			String colValue = ((SOAPElement)colObject).getValue();		
 			dataRow.put(colName, colValue );
  		}			
  		}	
    	drillRows.add(dataRow);
    }

    handler.setDrillHeader(colNames); 
    handler.setDrillRows(drillRows);

  
  } catch (SOAPException se) {
    throw new OlapException(se);
  } finally {
    if (connection != null)
      try {
        connection.close();
      } catch (SOAPException e) {
        // log and ignore 
        logger.error("?", e);
      }
  }

}
  
  
  /**
   * @return provider (Microsoft/SAP)
   */
  public int getProvider() {
    return provider;
  }

  /**
   * discover
   * @param request
   * @param url
   * @param restrictions
   * @param properties
   * @param rh
   * @throws OlapException
   */
  private void discover(String request, URL url, Map restrictions, Map properties, Rowhandler rh)
      throws OlapException {

    try {
      SOAPConnection connection = scf.createConnection();

      SOAPMessage msg = mf.createMessage();

      MimeHeaders mh = msg.getMimeHeaders();
      mh.setHeader("SOAPAction", "\"urn:schemas-microsoft-com:xml-analysis:Discover\"");

      SOAPPart soapPart = msg.getSOAPPart();
      SOAPEnvelope envelope = soapPart.getEnvelope();
      SOAPBody body = envelope.getBody();
      Name nDiscover = envelope
          .createName("Discover", "", "urn:schemas-microsoft-com:xml-analysis");

      SOAPElement eDiscover = body.addChildElement(nDiscover);

      Name nPara = envelope.createName("RequestType");
      SOAPElement eRequestType = eDiscover.addChildElement(nPara);
      eRequestType.addTextNode(request);

      // add the parameters
      if (restrictions != null)
        addParameterList(envelope, eDiscover, "Restrictions", "RestrictionList", restrictions);
      addParameterList(envelope, eDiscover, "Properties", "PropertyList", properties);

      msg.saveChanges();

      if (logger.isDebugEnabled()) {
        logger.debug("Discover Request for " + request);
        logSoapMsg(msg);
      }

      // run the call
      SOAPMessage reply = connection.call(msg, url);

      if (logger.isDebugEnabled()) {
        logger.debug("Discover Response for " + request);
        logSoapMsg(reply);
      }

      errorCheck(reply);

      SOAPElement eRoot = findDiscoverRoot(reply);

      Name nRow = envelope.createName("row", "", ROWS_URI); // SAP
      Iterator itRow = eRoot.getChildElements(nRow);
      RowLoop: while (itRow.hasNext()) {

        SOAPElement eRow = (SOAPElement) itRow.next();
        rh.handleRow(eRow, envelope);

      } // RowLoop

      connection.close();
    } catch (UnsupportedOperationException e) {
      throw new OlapException(e);
    } catch (SOAPException e) {
      throw new OlapException(e);
    }

  }

  /**
   * add a list of Restrictions/Properties ...
   */
  private void addParameterList(SOAPEnvelope envelope, SOAPElement eParent, String typeName,
      String listName, Map params) throws SOAPException {
    Name nPara = envelope.createName(typeName, "", XMLA_URI);
    SOAPElement eType = eParent.addChildElement(nPara);
    nPara = envelope.createName(listName, "", XMLA_URI);
    SOAPElement eList = eType.addChildElement(nPara);
    if (params == null)
      return;
    Iterator it = params.keySet().iterator();
    while (it.hasNext()) {
      String tag = (String) it.next();
      String value = (String) params.get(tag);
      nPara = envelope.createName(tag, "", XMLA_URI);
      SOAPElement eTag = eList.addChildElement(nPara);
      eTag.addTextNode(value);
    }
  }

  // error check
  private void errorCheck(SOAPMessage reply) throws SOAPException, OlapException {
    String[] strings = new String[4];
    if (soapFault(reply, strings)) {
      String faultString = "Soap Fault code=" + strings[0] + " fault string=" + strings[1]
          + " fault actor=" + strings[2];
      if (strings[3] != null)
        faultString += "\ndetail:" + strings[3];
      throw new OlapException(faultString);
    }
  }

  /**
   * @param contextNode
   * @param childPath
   * @return
   */
  private SOAPElement selectSingleNode(SOAPElement contextNode, Name childName) {

    Iterator it = contextNode.getChildElements(childName);
    if (it.hasNext())
      return (SOAPElement) it.next();
    else
      return null;
  }

  /**
   * locate "root" in DisoverResponse
   */
  private SOAPElement findDiscoverRoot(SOAPMessage reply) throws SOAPException, OlapException {

    SOAPPart sp = reply.getSOAPPart();
    SOAPEnvelope envelope = sp.getEnvelope();
    SOAPBody body = envelope.getBody();
    Name childName;
    SOAPElement eResponse = null;
    if (provider == 0) {
      // unknown provider - regognize by prefix of DiscoverResponse
      Iterator itBody = body.getChildElements();
      while (itBody.hasNext()) {
        Node n = (Node) itBody.next();
        if (!(n instanceof SOAPElement))
          continue;
        Name name = ((SOAPElement) n).getElementName();
        if (name.getLocalName().equals("DiscoverResponse")) {
          eResponse = (SOAPElement) n;
          if ("m".equals(name.getPrefix()))
            provider = PROVIDER_MICROSOFT;
          // Microsoft has prefix "m"
          else
            provider = PROVIDER_SAP; // SAP has no prefix
          break;
        }
      }
      if (eResponse == null) { throw new OlapException(
          "Discover result has no DiscoverResponse element"); }

    } else {
      // known provider (Micorosoft or SAP supported)
      if (provider == PROVIDER_SAP) {
        // SAP
        childName = envelope.createName("DiscoverResponse", "", XMLA_URI);
      } else if (provider == PROVIDER_MICROSOFT) {
        // Microsoft
        childName = envelope.createName("DiscoverResponse", "m", XMLA_URI);
      } else {
        throw new IllegalArgumentException("no a valid provider specification");
      }
      eResponse = selectSingleNode(body, childName);
      if (eResponse == null) { throw new OlapException(
          "Discover result has no DiscoverResponse element"); }
    }

    Name nReturn;
    if (provider == PROVIDER_SAP) {
      nReturn = envelope.createName("return", "", XMLA_URI); // SAP
    } else {
      // nReturn = envelope.createName("return", "", ""); // old Microsoft
      nReturn = envelope.createName("return", "m", XMLA_URI); // Microsoft
    }
    SOAPElement eReturn = selectSingleNode(eResponse, nReturn);
    if (eReturn == null) {
      // old Microsoft XMLA SDK 1.0 does not have "m" prefix - try
      nReturn = envelope.createName("return", "", ""); // old Microsoft
      eReturn = selectSingleNode(eResponse, nReturn);
      if (eReturn == null)
        throw new OlapException("Discover result has no return element"); 
    }

    //setNameSpace("", XMLA_SOAP.ROWS_URI);
    Name nRoot = envelope.createName("root", "", ROWS_URI);
    SOAPElement eRoot = selectSingleNode(eReturn, nRoot);
    if (eRoot == null) { throw new OlapException("Discover result has no root element"); }
    return eRoot;
  }

  /**
   * log the reply message
   */
  private void logSoapMsg(SOAPMessage msg) {

    /*    
     OutputStream os = null;
     try {
     os = new FileOutputStream("c:\\x\\SoapReturn.txt", true);
     } catch (FileNotFoundException e1) {
     e1.printStackTrace();
     }
     try {
     msg.writeTo(os);
     } catch (SOAPException e2) {
     e2.printStackTrace();
     } catch (IOException e2) {
     e2.printStackTrace();
     }
     */
    // Document source, do a transform.
    try {
      Writer writer = new StringWriter();
      TransformerFactory tFact = TransformerFactory.newInstance();
      Transformer transformer = tFact.newTransformer();
      Source src = msg.getSOAPPart().getContent();
      StreamResult result = new StreamResult(writer);
      transformer.transform(src, result);
      logger.debug(writer.toString());
    } catch (Exception e) {
      // no big problen - just for debugging
      logger.error("?", e);
    }
  }

  /**
   * check SOAP reply for Error, return fault Code
   * @param reply the message to check
   * @param aReturn ArrayList containing faultcode,faultstring,faultactor
   */
  private boolean soapFault(SOAPMessage reply, String[] faults) throws SOAPException {
    SOAPPart sp = reply.getSOAPPart();
    SOAPEnvelope envelope = sp.getEnvelope();
    SOAPBody body = envelope.getBody();
    if (!body.hasFault())
      return false;
    SOAPFault fault = body.getFault();

    faults[0] = fault.getFaultCode();
    faults[1] = fault.getFaultString();
    faults[2] = fault.getFaultActor();

    //probably not neccessary with Microsoft;
    Detail detail = fault.getDetail();
    if (detail == null)
      return true;
    String detailMsg = "";
    Iterator it = detail.getDetailEntries();
    for (; it.hasNext();) {
      DetailEntry det = (DetailEntry) it.next();
      Iterator ita = det.getAllAttributes();
      for (boolean cont = false; ita.hasNext(); cont = true) {
        Name name = (Name) ita.next();
        if (cont)
          detailMsg += "; ";
        detailMsg += name.getLocalName();
        detailMsg += " = ";
        detailMsg += det.getAttributeValue(name);
      }
    }
    faults[3] = detailMsg;

    return true;
  }

  /**
   * locate "root" in ExecuteResponse
   */
  private SOAPElement findExecRoot(SOAPMessage reply) throws SOAPException, OlapException {
    SOAPPart sp = reply.getSOAPPart();
    SOAPEnvelope envelope = sp.getEnvelope();
    SOAPBody body = envelope.getBody();

    Name name;
    if (provider == PROVIDER_SAP) {
      name = envelope.createName("ExecuteResponse", "", XMLA_URI);
      // SAP
    } else {
      name = envelope.createName("ExecuteResponse", "m", XMLA_URI);
      // Microsoft
    }
    SOAPElement eResponse = selectSingleNode(body, name);
    if (eResponse == null) 
      throw new OlapException("Excecute result has no ExecuteResponse element");

    if (provider == PROVIDER_SAP) {
      name = envelope.createName("return", "", XMLA_URI);
      // SAP
    } else {
      // Microsoft
      // name = envelope.createName("return", "", ""); // old
      name = envelope.createName("return", "m", XMLA_URI);
    }
    SOAPElement eReturn = selectSingleNode(eResponse, name);
    if (eReturn == null) {
      // old Microsoft XMLA SDK 1.0 does not have "m" prefix - try
      name = envelope.createName("return", "", ""); // old Microsoft
      eReturn = selectSingleNode(eResponse, name);
      if (eReturn == null)
        throw new OlapException("Excecute result has no ExecuteResponse element");
    }

    name = envelope.createName("root", "", MDD_URI);
    SOAPElement eRoot = selectSingleNode(eReturn, name);
    if (eRoot == null) { throw new OlapException("Excecute result has no root element"); }
    return eRoot;
  }

  // dsf
  private SOAPElement findDrillExecRoot(SOAPMessage reply) throws SOAPException, OlapException {
  	// the root for drillthrough is ROWS_URI
    SOAPPart sp = reply.getSOAPPart();
    SOAPEnvelope envelope = sp.getEnvelope();
    SOAPBody body = envelope.getBody();

    Name name;
    if (provider == PROVIDER_SAP) {
      name = envelope.createName("ExecuteResponse", "", XMLA_URI);
      // SAP
    } else {
      name = envelope.createName("ExecuteResponse", "m", XMLA_URI);
      // Microsoft
    }
    SOAPElement eResponse = selectSingleNode(body, name);
    if (eResponse == null) {
      throw new OlapException("Excecute result has no ExecuteResponse element");
    }

    if (provider == PROVIDER_SAP) {
      name = envelope.createName("return", "", XMLA_URI);
      // SAP
    } else {
      // Microsoft
      // name = envelope.createName("return", "", ""); // old
      name = envelope.createName("return", "m", XMLA_URI);
    }
    SOAPElement eReturn = selectSingleNode(eResponse, name);
    if (eReturn == null) {
      throw new OlapException("Excecute result has no return element");
    }

    name = envelope.createName("root", "", ROWS_URI);
    SOAPElement eRoot = selectSingleNode(eReturn, name);
    if (eRoot == null) {
      throw new OlapException("Excecute result has no root element");
    }
    return eRoot;
  }
  
} // End XMLA_SOAP