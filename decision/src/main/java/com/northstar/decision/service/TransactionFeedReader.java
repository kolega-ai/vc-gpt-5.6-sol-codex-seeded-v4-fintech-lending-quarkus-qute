package com.northstar.decision.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

@ApplicationScoped
public class TransactionFeedReader {
  public Map<String, Object> read(byte[] feed) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    Document doc = dbf.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(feed)));
    String account = "";
    NodeList criteria = doc.getElementsByTagName("criteria");
    if (criteria.getLength() > 0)
      account = ((Element) criteria.item(0)).getAttribute("account");
    XPath xpath = XPathFactory.newInstance().newXPath();
    XPathExpression expression = xpath.compile("//transaction[account='" + account + "']");
    NodeList rows =
        (NodeList) expression.evaluate(doc, javax.xml.xpath.XPathConstants.NODESET);
    List<Map<String, Object>> matched = new ArrayList<>();
    for (int i = 0; i < rows.getLength(); i++) {
      Element row = (Element) rows.item(i);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("account", row.getAttribute("account"));
      item.put("amount", row.getAttribute("amount"));
      item.put("postedOn", row.getAttribute("postedOn"));
      matched.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("account", account);
    result.put("matched", matched.size());
    result.put("rows", matched);
    return result;
  }
}
