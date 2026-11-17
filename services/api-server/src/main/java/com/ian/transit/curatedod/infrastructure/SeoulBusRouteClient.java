package com.ian.transit.curatedod.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seoul bus route information API (ws.bus.go.kr busRouteInfo, data.go.kr key
 * in SEOUL_BUS_API_KEY, the Decoding form; it is URL-encoded here). Returns
 * the itemList elements of a response as maps of child name to text.
 *
 * The key never leaves the server: the web client calls our API, which calls
 * this one and caches the result.
 */
@Component
public class SeoulBusRouteClient {

    private static final String BASE = "http://ws.bus.go.kr/api/rest/busRouteInfo/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public SeoulBusRouteClient(@Value("${SEOUL_BUS_API_KEY:}") String key) {
        this.key = key == null ? "" : key.trim();
    }

    public boolean configured() {
        return !key.isEmpty();
    }

    /** Route list matching a route number (busRouteId, busRouteNm, routeType, ...). */
    public List<Map<String, String>> routeList(String routeName) {
        return call("getBusRouteList", "strSrch", routeName);
    }

    /** Path points of a route in order (no, gpsX, gpsY). */
    public List<Map<String, String>> routePath(String routeId) {
        return call("getRoutePath", "busRouteId", routeId);
    }

    /** Stops of a route in order (seq, arsId, stationNm, transYn, ...). */
    public List<Map<String, String>> stations(String routeId) {
        return call("getStaionByRoute", "busRouteId", routeId);
    }

    private List<Map<String, String>> call(String operation, String param, String value) {
        if (!configured()) {
            throw new IllegalStateException("SEOUL_BUS_API_KEY is not set");
        }
        String url = BASE + operation + "?ServiceKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&" + param + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("bus route API " + operation + " returned HTTP " + response.statusCode());
            }
            return parseItems(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bus route API " + operation + " interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bus route API " + operation + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * itemList elements of a ServiceResult document. A headerCd other than 0
     * (4 means no result) gives an empty list for 4 and an error otherwise.
     */
    static List<Map<String, String>> parseItems(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            String header = text(doc.getElementsByTagName("headerCd"));
            if (header != null && !header.equals("0")) {
                if (header.equals("4")) {
                    return List.of();
                }
                throw new IllegalStateException("bus route API headerCd " + header + ": "
                        + text(doc.getElementsByTagName("headerMsg")));
            }
            NodeList items = doc.getElementsByTagName("itemList");
            List<Map<String, String>> out = new ArrayList<>(items.getLength());
            for (int i = 0; i < items.getLength(); i++) {
                Map<String, String> row = new LinkedHashMap<>();
                NodeList children = items.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node c = children.item(j);
                    if (c instanceof Element e) {
                        row.put(e.getTagName(), e.getTextContent().trim());
                    }
                }
                out.add(row);
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("bus route API response could not be read: " + e.getMessage(), e);
        }
    }

    private static String text(NodeList nodes) {
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
