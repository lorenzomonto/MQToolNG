/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Helpers;

import MQApi.Connection.SecurePasswordStore;
import javax.xml.XMLConstants;
import MQApi.Models.Query.ConnectionDetailModel;
import UI.MainWindow;
import UI.Models.MQRC.MQRCParentModel;
import UI.Models.SettingsModel;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 *
 * @author jzhou
 */
public class XMLHelper {
    private static String elementName = "ConnectionDetail";
    private static String rootName = "ConnectionSetting";
    private static String settingPath = "Settings.xml";
    /**
     * XML parser that ignores DTDs and external entities. Message contents are untrusted:
     * with the default parser a message could make the tool read local files or open
     * network connections when it is displayed (XXE).
     */
    public static DocumentBuilderFactory SecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    public static TransformerFactory SecureTransformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    // Connection fields that are written encrypted, as "Encrypted<name>" elements
    private static final java.util.List<String> SECRET_FIELDS = java.util.Arrays.asList("Password", "TrustStorePassword", "KeyStorePassword");

    public static void WriteConnectionModelToXml(ArrayList<ConnectionDetailModel> models, String filePath){
        
        try {
            DocumentBuilderFactory dbf = SecureDocumentBuilderFactory();
            
            DocumentBuilder db = dbf.newDocumentBuilder();       
            Document document = db.newDocument();
            Element rootElement = document.createElement(rootName);
            for(ConnectionDetailModel model : models){
                Element connectionEle = document.createElement(elementName);
                for(Field field : model.getClass().getFields()){
                    String name = field.getName();
                    String value = field.get(model) != null ? (field.get(model)).toString() : null;
                    if(SECRET_FIELDS.contains(name)){
                        // Never written in clear: encrypted with DPAPI, or not saved at all
                        name = "Encrypted" + name;
                        value = SecurePasswordStore.Protect(value);
                    }
                    if(value != null){
                        Element ele = document.createElement(name);
                        ele.appendChild(document.createTextNode(value));
                        connectionEle.appendChild(ele);
                    }
                }
                rootElement.appendChild(connectionEle);
            }
            document.appendChild(rootElement);
            Transformer tr = SecureTransformerFactory().newTransformer();
            try(FileOutputStream out = new FileOutputStream(filePath)){
                tr.transform(new DOMSource(document), new StreamResult(out));
            }
        } catch (Exception ex) {
            Logger.getLogger(XMLHelper.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static ArrayList<ConnectionDetailModel> ReadConnectionModelFromXml(String filePath){
        ArrayList<ConnectionDetailModel> models = new ArrayList<ConnectionDetailModel>();
         try {
            DocumentBuilderFactory dbf = SecureDocumentBuilderFactory();
            DocumentBuilder db = dbf.newDocumentBuilder();
            File file = new File(filePath);

            if(file.exists() && !file.isDirectory()) {
               Document document;
               try(FileInputStream fileStream = new FileInputStream(file)){
                   document = db.parse(fileStream);
               }
               boolean hasClearPasswords = false;
               NodeList nodes = document.getElementsByTagName(elementName);
               for(int i = 0; i < nodes.getLength(); i++){
                   Node node = nodes.item(i);
                   ConnectionDetailModel model = new ConnectionDetailModel();
                   for(Field field : model.getClass().getFields()){
                       String fieldName = field.getName();
                       String fieldValue = getNodeValue(node, fieldName);
                       field.set(model, fieldValue);
                   }
                   for(String secret : SECRET_FIELDS){
                       Field field = model.getClass().getField(secret);
                       Object clear = field.get(model);
                       if(clear != null && !clear.toString().isEmpty()){
                           // Written in clear by older versions
                           hasClearPasswords = true;
                       }
                       else{
                           // Null when it cannot be decrypted (another Windows user or computer): asked when connecting
                           field.set(model, SecurePasswordStore.Unprotect(getNodeValue(node, "Encrypted" + secret)));
                       }
                   }
                   models.add(model);
               }
               if(hasClearPasswords){
                   // Rewrite the file at once so the passwords in clear disappear from the disk
                   WriteConnectionModelToXml(models, filePath);
               }
            }

         }catch(Exception ex){
             Logger.getLogger(XMLHelper.class.getName()).log(Level.SEVERE, null, ex);
         }
        return models;
    }

    public static SettingsModel ReadSettingsModelFromXml(){
        SettingsModel model = new SettingsModel();
        model.SkinName = "Light";
         try {
            DocumentBuilderFactory dbf = SecureDocumentBuilderFactory();
            DocumentBuilder db = dbf.newDocumentBuilder();
            File file = new File(settingPath);
            if(file.exists() && !file.isDirectory()) {
               FileInputStream fileStream = new FileInputStream(file);
               Document document = db.parse(fileStream);
               NodeList nodes = document.getElementsByTagName("Skin");
               model.SkinName = nodes.item(0).getTextContent();              
            }

         }catch(Exception ex){
             Logger.getLogger(XMLHelper.class.getName()).log(Level.SEVERE, null, ex);
         }
        return model;
    }
    public static void WriteSettingModelToXml(SettingsModel model){
        
        try {
            DocumentBuilderFactory dbf = SecureDocumentBuilderFactory();
            
            DocumentBuilder db = dbf.newDocumentBuilder();       
            Document document = db.newDocument();
            Element rootElement = document.createElement("Settings");
            Element skinEle = document.createElement("Skin");
            skinEle.appendChild(document.createTextNode(model.SkinName));
            rootElement.appendChild(skinEle);
            document.appendChild(rootElement);
            Transformer tr = SecureTransformerFactory().newTransformer();
            tr.transform(new DOMSource(document), new StreamResult(new FileOutputStream(settingPath)));
        } catch (Exception ex) {
            Logger.getLogger(XMLHelper.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void WriteMQRCModelToXml(){
        MQRCParentModel model = new MQRCParentModel();
        String filePath = "MQRCDefValue.xml";
        try {
            DocumentBuilderFactory dbf = SecureDocumentBuilderFactory();
            
            DocumentBuilder db = dbf.newDocumentBuilder();       
            Document document = db.newDocument();
            Element rootElement = document.createElement("MQRCModelDefValues");
            for(Field field : model.getClass().getFields()){
                String name = field.getName();
                Element modelEle = document.createElement(name);
                Object childModel = field.get(model);
                for(Field modelField : childModel.getClass().getFields()){
                    String propertyName = modelField.getName();
                    String value = modelField.get(childModel) != null ? (modelField.get(childModel)).toString() : " ";
                    Element ele = document.createElement(propertyName);
                    ele.appendChild(document.createTextNode(value));
                    modelEle.appendChild(ele);
                }
                rootElement.appendChild(modelEle);
            }
            document.appendChild(rootElement);
            Transformer tr = SecureTransformerFactory().newTransformer();
            try(FileOutputStream out = new FileOutputStream(filePath)){
                tr.transform(new DOMSource(document), new StreamResult(out));
            }
        } catch (Exception ex) {
            Logger.getLogger(XMLHelper.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Indents an XML message for reading. Only the whitespace between tags changes: the declaration,
     * namespaces, comments, the order of the attributes and the text stay as they are (a DOM
     * serializer would drop or rewrite them).
     */
    public static String XMLStringFormat(String xml) {
        return UI.Misc.XmlSyntax.Indent(xml.trim(), "  ");
    }
    
    public static boolean IsXML(String xml) {
         try {
            final InputSource src = new InputSource(new StringReader(xml));
            final Node document = SecureDocumentBuilderFactory().newDocumentBuilder().parse(src).getDocumentElement();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private static String getNodeValue(Node node, String fieldName){
        Node targetNode = findNode(node, fieldName);
        
        return targetNode != null? targetNode.getTextContent() : null;
    }
     
    private static Node findNode (Node node, String fieldName){
         NodeList Nodes = node.getChildNodes();
        for(int i = 0; i < Nodes.getLength(); i++){
            Node fieldNode = Nodes.item(i);
            String name = fieldNode.getNodeName();
            if(name != null && name.equals(fieldName)){
                return fieldNode;
            }
        }        
        return null;       
    }
}
