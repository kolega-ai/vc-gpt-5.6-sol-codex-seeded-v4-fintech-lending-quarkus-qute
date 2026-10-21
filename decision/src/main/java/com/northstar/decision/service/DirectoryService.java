package com.northstar.decision.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import java.util.*;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DirectoryService {
  @ConfigProperty(name = "security.directory-url")
  String directoryUrl;

  @ConfigProperty(name = "security.directory-bind-dn")
  String directoryBindDn;

  @ConfigProperty(name = "security.directory-base-dn")
  String directoryBaseDn;

  @ConfigProperty(name = "security.directory-credential")
  String directoryCredential;

  public Map<String, Object> resolve(String principal) {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    env.put(Context.PROVIDER_URL, directoryUrl);
    env.put(Context.SECURITY_AUTHENTICATION, "simple");
    env.put(Context.SECURITY_PRINCIPAL, directoryBindDn);
    env.put(Context.SECURITY_CREDENTIALS, directoryCredential);
    DirContext ctx = null;
    try {
      ctx = new InitialDirContext(env);
      String filter = "(&(objectClass=person)(uid=" + principal + "))";
      SearchControls controls = new SearchControls();
      controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
      controls.setReturningAttributes(new String[] {"cn", "mail", "department"});
      NamingEnumeration<SearchResult> found = ctx.search(directoryBaseDn, filter, controls);
      Map<String, Object> entry = new LinkedHashMap<>();
      if (found.hasMore()) {
        Attributes attributes = found.next().getAttributes();
        entry.put("displayName", text(attributes, "cn"));
        entry.put("mail", text(attributes, "mail"));
        entry.put("department", text(attributes, "department"));
      }
      return entry;
    } catch (NamingException e) {
      throw new BadRequestException("Directory entry could not be resolved");
    } finally {
      if (ctx != null)
        try {
          ctx.close();
        } catch (NamingException ignored) {
        }
    }
  }

  private String text(Attributes attributes, String name) throws NamingException {
    Attribute attribute = attributes.get(name);
    return attribute == null ? null : String.valueOf(attribute.get());
  }
}
