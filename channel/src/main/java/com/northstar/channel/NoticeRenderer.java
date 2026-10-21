package com.northstar.channel;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringWriter;
import java.util.Map;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

@ApplicationScoped
public class NoticeRenderer {
  public String render(String source, Map<String, Object> variables) {
    VelocityContext context = new VelocityContext();
    variables.forEach(context::put);
    StringWriter writer = new StringWriter();
    Velocity.evaluate(context, writer, "partnerNotice", source);
    return writer.toString();
  }
}
