package com.yahoo.vespa.hosted.node.verification.spec.retrievers;

import com.yahoo.vespa.hosted.node.verification.commons.CommandExecutor;
import com.yahoo.vespa.hosted.node.verification.commons.OutputParser;
import com.yahoo.vespa.hosted.node.verification.commons.ParseInstructions;
import com.yahoo.vespa.hosted.node.verification.commons.ParseResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by olaa on 30/06/2017.
 */
public class MemoryRetriever implements HardwareRetriever {

    private static final String MEMORY_INFO_COMMAND = "cat /proc/meminfo";
    final static String searchWord = "MemTotal";
    final static String regexSplit = ":\\s";
    final static int searchElementIndex = 0;
    final static int returnElementIndex = 1;
    private static final Logger logger = Logger.getLogger(MemoryRetriever.class.getName());
    private final HardwareInfo hardwareInfo;
    private final CommandExecutor commandExecutor;

    public MemoryRetriever(HardwareInfo hardwareInfo, CommandExecutor commandExecutor) {
        this.hardwareInfo = hardwareInfo;
        this.commandExecutor = commandExecutor;
    }


    public void updateInfo() {
        try {
            ArrayList<String> commandOutput = commandExecutor.executeCommand(MEMORY_INFO_COMMAND);
            ParseResult parseResult = parseMemInfoFile(commandOutput);
            updateMemoryInfo(parseResult);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to retrieve memory info", e);
        }
    }

    protected ParseResult parseMemInfoFile(ArrayList<String> commandOutput) {
        ArrayList<String> searchWords = new ArrayList<>(Arrays.asList(searchWord));
        ParseInstructions parseInstructions = new ParseInstructions(searchElementIndex, returnElementIndex, regexSplit, searchWords);
        ParseResult parseResult = OutputParser.parseSingleOutput(parseInstructions, commandOutput);
        ;
        return parseResult;
    }

    protected void updateMemoryInfo(ParseResult parseResult) {
        double memory = convertKBToGB(parseResult.getValue());
        hardwareInfo.setMinMainMemoryAvailableGb(memory);
    }

    protected double convertKBToGB(String totMem) {
        String[] split = totMem.split(" ");
        double value = Double.parseDouble(split[0]);
        double kiloToGiga = 1000000.0;
        return value / kiloToGiga;
    }
}
