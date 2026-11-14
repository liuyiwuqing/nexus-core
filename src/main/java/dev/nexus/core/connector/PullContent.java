package dev.nexus.core.connector;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

/** Bounded provider formats. Text and links are data; entries never initiate extra requests. */
final class PullContent {
    record Item(String key, String title, String content, String url, Instant published) {}
    private PullContent() {}
    static List<Item> rss(byte[] raw) {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",64);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var parser=factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
                @Override public void fatalError(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}
                @Override public void error(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}
            });
            Element root=parser.parse(new ByteArrayInputStream(raw)).getDocumentElement();
            boolean atom="feed".equals(root.getLocalName())&&"http://www.w3.org/2005/Atom".equals(root.getNamespaceURI());
            Element container=atom?root:child(root,"channel");
            if(!atom&&(!"rss".equals(root.getTagName())||!"2.0".equals(root.getAttribute("version"))||container==null))throw invalid();
            var items=new LinkedHashMap<String,Item>();int count=0;
            for(Element entry:children(container,atom?"entry":"item")) {
                if(++count>100)throw invalid();
                String title=text(entry,"title"),content=text(entry,atom?"content":"description");
                if(content.isBlank()&&atom)content=text(entry,"summary");
                if(title.isBlank())title=content;if(content.isBlank())content=title;
                String link=atom?atomLink(entry):text(entry,"link"),key=text(entry,atom?"id":"guid");
                if(key.isBlank())key=link;
                if(key.isBlank()||key.length()>4096||title.isBlank())throw invalid();
                Instant published=date(text(entry,atom?"updated":"pubDate"),atom);
                title=plain(title,160);content=plain(content,8192);
                if(title.isBlank()||content.isBlank())throw invalid();
                items.putIfAbsent(key,new Item(key,title,content,safeLink(link),published));
            }
            return List.copyOf(items.values());
        }catch(Exception failure){throw invalid();}
    }
    static List<Item> weather(byte[] raw,ObjectMapper json,String name) {
        try {
            var body=json.readTree(raw);var current=body.path("current");
            var units=body.path("current_units");
            if(!current.path("time").isIntegralNumber()||!current.path("temperature_2m").isNumber()||!current.path("relative_humidity_2m").isNumber()||!current.path("weather_code").isIntegralNumber()
                ||!"°C".equals(units.path("temperature_2m").asText())||!"%".equals(units.path("relative_humidity_2m").asText()))throw invalid();
            Instant time=Instant.ofEpochSecond(current.path("time").asLong());
            double temperature=current.path("temperature_2m").asDouble(),humidity=current.path("relative_humidity_2m").asDouble();int code=current.path("weather_code").asInt();
            if(!Double.isFinite(temperature)||temperature< -100||temperature>70||!Double.isFinite(humidity)||humidity<0||humidity>100||code<0||code>99
                ||time.isBefore(Instant.now().minusSeconds(86400))||time.isAfter(Instant.now().plusSeconds(3600)))throw invalid();
            String content="室外天气："+temperature+" °C，相对湿度 "+humidity+"%，WMO 天气代码 "+code+"。数据时间 "+time+"。来源 Open-Meteo。";
            return List.of(new Item("hour:"+Math.floorDiv(time.getEpochSecond(),3600),plain(name+"天气",160),content,"https://open-meteo.com/",time));
        }catch(Exception failure){throw invalid();}
    }
    private static String plain(String value,int max){String text=Jsoup.parse(value).text().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]"," ");if(text.length()<=max)return text;return text.substring(0,Character.isHighSurrogate(text.charAt(max-1))?max-1:max);}
    private static String safeLink(String value){try{var uri=URI.create(value);return value.length()<=2048&&List.of("https","http").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null?value:"";}catch(Exception invalid){return "";}}
    private static Instant date(String value,boolean atom){try{return atom?Instant.parse(value):ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception ignored){return null;}}
    private static String atomLink(Element entry){for(Element link:children(entry,"link"))if(link.getAttribute("rel").isBlank()||link.getAttribute("rel").equals("alternate"))return link.getAttribute("href");return "";}
    private static String text(Element parent,String name){Element node=child(parent,name);return node==null?"":node.getTextContent().strip();}
    private static Element child(Element parent,String name){var all=children(parent,name);return all.isEmpty()?null:all.getFirst();}
    private static List<Element> children(Element parent,String name){var result=new ArrayList<Element>();for(var node=parent.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element element&&name.equals(element.getLocalName()))result.add(element);return result;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid_provider_response");}
}
