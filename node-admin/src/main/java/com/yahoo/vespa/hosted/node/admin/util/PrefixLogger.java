package com.yahoo.vespa.hosted.node.admin.util;

import com.yahoo.vespa.hosted.node.admin.docker.ContainerName;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author valerijf
 */
public class PrefixLogger {
    private String prefix;
    private Logger logger;

    private PrefixLogger(Class clazz, String prefix) {
        this.logger = Logger.getLogger(clazz.getName());
        this.prefix = prefix + ": ";
    }

    public static PrefixLogger getNodeAdminLogger(Class clazz) {
        return new PrefixLogger(clazz, "NodeAdmin");
    }

    public static PrefixLogger getNodeAgentLogger(Class clazz, ContainerName containerName) {
        return new PrefixLogger(clazz, "NodeAgent-" + containerName.asString());
    }

    public void log(Level level, String msg, Throwable thrown) {
        logger.log(level, prefix + msg, thrown);
    }

    public void log(Level level, String msg) {
        logger.log(level, prefix + msg);
    }
}
