package com.yahoo.vespa.hosted.node.verification.spec.noderepo;

import com.yahoo.vespa.hosted.node.verification.spec.yamasreport.YamasSpecReport;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by olaa on 14/07/2017.
 * Checks if all additional Ipv6 addresses has the same hostname as the main Ipv6 address.
 */

public class IPAddressVerifier {

    private static final Logger logger = Logger.getLogger(IPAddressVerifier.class.getName());

    public void reportFaultyIpAddresses(NodeRepoJsonModel nodeRepoJsonModel, YamasSpecReport yamasSpecReport) {
        String[] faultyIpAddresses = getFaultyIpAddresses(nodeRepoJsonModel.getIpv6Address(), nodeRepoJsonModel.getAdditionalIpAddresses());
        if (faultyIpAddresses.length > 0) {
            yamasSpecReport.setFaultyIpAddresses(faultyIpAddresses);
        }
    }

    protected String reverseLookUp(String ipAddress) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        String ipAddressInLookupFormat = convertToLookupFormat(ipAddress);
        String attributeName = ipAddress;
        DirContext ctx = new InitialDirContext(env);
        Attributes attrs = ctx.getAttributes(attributeName, new String[]{"PTR"});
        for (NamingEnumeration<? extends Attribute> ae = attrs.getAll(); ae.hasMoreElements(); ) {
            Attribute attr = ae.next();
            Enumeration<?> vals = attr.getAll();
            if (vals.hasMoreElements()) {
                return vals.nextElement().toString();
            }
        }
        ctx.close();
        return "";
    }

    protected String convertToLookupFormat(String ipAddress) {
        StringBuilder newIpAddress = new StringBuilder();
        String doubleColonReplacement = "0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.";
        String domain = "ip6.arpa";
        String[] hextets = ipAddress.split(":");
        for (int i = hextets.length - 1; i >= 0; i--) {
            String reversedHextet = new StringBuilder(hextets[i]).reverse().toString();
            if (reversedHextet.equals("")) {
                newIpAddress.append(doubleColonReplacement);
                continue;
            }
            String trailingZeroes = "0000";
            String paddedHextet = (reversedHextet + trailingZeroes).substring(0, trailingZeroes.length());
            String punctuatedHextet = paddedHextet.replaceAll(".(?=)", "$0.");
            newIpAddress.append(punctuatedHextet);
        }
        newIpAddress.append(domain);
        return newIpAddress.toString();
    }

    public String[] getFaultyIpAddresses(String ipAddress, String[] additionalIpAddresses) {
        if (ipAddress == null || additionalIpAddresses == null || additionalIpAddresses.length == 0)
            return new String[0];
        String realHostname;
        ArrayList<String> faultyIpAddresses = new ArrayList<>();
        try {
            realHostname = reverseLookUp(ipAddress);
        } catch (NamingException e) {
            logger.log(Level.WARNING, "Unable to look up host name of address " + ipAddress, e);
            return new String[0];
        }
        for (String additionalIpAddress : additionalIpAddresses) {
            addIfFaultyIpAddress(realHostname, additionalIpAddress, faultyIpAddresses);
        }
        return faultyIpAddresses.stream().toArray(String[]::new);
    }

    private void addIfFaultyIpAddress(String realHostname, String additionalIpAddress, ArrayList<String> faultyIpAddresses) {
        try {
            String additionalHostName = reverseLookUp(additionalIpAddress);
            if (!realHostname.equals(additionalHostName)) {
                faultyIpAddresses.add(additionalIpAddress);
            }
        } catch (NamingException e) {
            logger.log(Level.WARNING, "Unable to retrieve hostname of additional address: " + additionalIpAddress, e);
        }
    }

}
