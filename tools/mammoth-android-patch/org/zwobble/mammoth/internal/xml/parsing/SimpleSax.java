package org.zwobble.mammoth.internal.xml.parsing;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.zwobble.mammoth.internal.util.PassThroughException;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Map;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toMap;

/**
 * mdvault Android patch of mammoth 1.9.0 SimpleSax.
 *
 * Android's libcore hardcodes {@code new SAXParserFactoryImpl()} in
 * {@code SAXParserFactory.newInstance()} and its Expat-based parser rejects
 * the Apache-namespace security features below with
 * {@code SAXNotRecognizedException}, killing every DOCX import on device.
 * This patch makes the feature setup best-effort. The DOCTYPE/XXE protection
 * those features provided is enforced upstream by mdvault's DocxXmlSanitizer,
 * which strips DOCTYPE declarations before the bytes reach mammoth.
 *
 * Only trySetFeature() differs from upstream. Rebuild instructions:
 * tools/mammoth-android-patch/README.md
 */
class SimpleSax {
    static void parseStream(InputStream input, SimpleSaxHandler handler) {
        parseInputSource(new InputSource(input), handler);
    }

    static void parseString(String value, SimpleSaxHandler handler) {
        parseInputSource(new InputSource(new StringReader(value)), handler);
    }

    private static void parseInputSource(InputSource inputSource, SimpleSaxHandler handler) {
        SAXParserFactory parserFactory = SAXParserFactory.newInstance();
        parserFactory.setNamespaceAware(true);
        try {
            trySetFeature(parserFactory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(parserFactory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(parserFactory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            SAXParser saxParser = parserFactory.newSAXParser();
            XMLReader xmlReader = saxParser.getXMLReader();
            xmlReader.setContentHandler(new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    ElementName name = new ElementName(uri, localName);
                    Map<ElementName, String> attributesMap = IntStream.range(0, attributes.getLength())
                        .boxed()
                        .collect(toMap(
                            index -> new ElementName(attributes.getURI(index), attributes.getLocalName(index)),
                            attributes::getValue
                        ));
                    handler.startElement(name, attributesMap);
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    handler.endElement();
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException {
                    handler.characters(new String(ch, start, length));
                }
            });
            xmlReader.parse(inputSource);
        } catch (IOException exception) {
            throw new PassThroughException(exception);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void trySetFeature(SAXParserFactory parserFactory, String name, boolean value) {
        try {
            parserFactory.setFeature(name, value);
        } catch (Exception exception) {
            // Unsupported on this platform (Android Expat). See class javadoc.
        }
    }
}
