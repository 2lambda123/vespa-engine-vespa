package com.yahoo.vespa.hosted.node.verification.spec.retrievers;

import com.yahoo.vespa.hosted.node.verification.commons.CommandExecutor;
import com.yahoo.vespa.hosted.node.verification.commons.OutputParser;
import com.yahoo.vespa.hosted.node.verification.commons.ParseInstructions;
import com.yahoo.vespa.hosted.node.verification.commons.ParseResult;
import org.apache.commons.exec.ExecuteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by olaa on 30/06/2017.
 */
public class NetRetriever implements HardwareRetriever {
    private static final String NET_FIND_INTERFACE = "/sbin/ifconfig";
    private static final String NET_CHECK_INTERFACE_SPEED = "/sbin/ethtool";
    private static final String SEARCH_WORD_INTERFACE_IP4 = "inet";
    private static final String SEARCH_WORD_INTERFACE_IPV6 = "inet6";
    private static final String SEARCH_WORD_INTERFACE_NAME = "eth.";
    private static final String SEARCH_WORD_INTERFACE_SPEED = "Speed";
    private static final String INTERFACE_NAME_REGEX_SPLIT = "\\s+";
    private static final String INTERFACE_NAME_SKIP_WORD = "lo";
    private static final String INTERFACE_NAME_SKIP_UNTIL_WORD = "";
    private static final int INTERFACE_NAME_SEARCH_ELEMENT_INDEX = 0;
    private static final int INTERFACE_NAME_RETURN_ELEMENT_INDEX = 0;
    private static final String INTERFACE_SPEED_REGEX_SPLIT = ":";
    private static final int INTERFACE_SPEED_SEARCH_ELEMENT_INDEX = 0;
    private static final int INTERFACE_SPEED_RETURN_ELEMENT_INDEX = 1;
    private static final String PING_NET_COMMAND = "ping6 -c 1 www.yahoo.com | grep transmitted";
    private static final String PING_SEARCH_WORD = "loss,";
    private static final String PING_SPLIT_REGEX_STRING = "\\s+";
    private static final int PING_SEARCH_ELEMENT_INDEX = 7;
    private static final int PING_RETURN_ELEMENT_INDEX = 5;
    private static final Logger logger = Logger.getLogger(NetRetriever.class.getName());
    private final HardwareInfo hardwareInfo;
    private final CommandExecutor commandExecutor;


    public NetRetriever(HardwareInfo hardwareInfo, CommandExecutor commandExecutor) {
        this.hardwareInfo = hardwareInfo;
        this.commandExecutor = commandExecutor;
    }

    public void updateInfo() {
        ArrayList<ParseResult> parseResults = findInterface();
        findInterfaceSpeed(parseResults);
        testPingResponse(parseResults);
        updateHardwareInfoWithNet(parseResults);
    }

    protected ArrayList<ParseResult> findInterface() {
        ArrayList<ParseResult> parseResults = new ArrayList<>();
        try {
            ArrayList<String> commandOutput = commandExecutor.executeCommand(NET_FIND_INTERFACE);
            parseResults = parseNetInterface(commandOutput);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to retrieve net interface. ", e);
        }
        return parseResults;
    }

    protected ArrayList<ParseResult> parseNetInterface(ArrayList<String> commandOutput) {
        ArrayList<String> searchWords = new ArrayList<>(Arrays.asList(SEARCH_WORD_INTERFACE_IP4, SEARCH_WORD_INTERFACE_IPV6, SEARCH_WORD_INTERFACE_NAME));
        ParseInstructions parseInstructions = new ParseInstructions(INTERFACE_NAME_SEARCH_ELEMENT_INDEX, INTERFACE_NAME_RETURN_ELEMENT_INDEX, INTERFACE_NAME_REGEX_SPLIT, searchWords);
        parseInstructions.setSkipWord(INTERFACE_NAME_SKIP_WORD);
        parseInstructions.setSkipUntilKeyword(INTERFACE_NAME_SKIP_UNTIL_WORD);
        ArrayList<ParseResult> parseResults = OutputParser.parseOutPutWithSkips(parseInstructions, commandOutput);
        return parseResults;
    }

    protected void findInterfaceSpeed(ArrayList<ParseResult> parseResults) {
        try {
            String interfaceName = findInterfaceName(parseResults);
            String command = NET_CHECK_INTERFACE_SPEED + " " + interfaceName;
            ArrayList<String> commandOutput = commandExecutor.executeCommand(command);
            ParseResult parseResult = parseInterfaceSpeed(commandOutput);
            parseResults.add(parseResult);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to retrieve interface speed. ", e);
        }
    }

    protected String findInterfaceName(ArrayList<ParseResult> parseResults) {
        for (ParseResult parseResult : parseResults) {
            if (!parseResult.getSearchWord().matches(SEARCH_WORD_INTERFACE_NAME)) continue;
            return parseResult.getValue();
        }
        return "";
    }

    protected ParseResult parseInterfaceSpeed(ArrayList<String> commandOutput) throws IOException {
        ArrayList<String> searchWords = new ArrayList<>(Arrays.asList(SEARCH_WORD_INTERFACE_SPEED));
        ParseInstructions parseInstructions = new ParseInstructions(INTERFACE_SPEED_SEARCH_ELEMENT_INDEX, INTERFACE_SPEED_RETURN_ELEMENT_INDEX, INTERFACE_SPEED_REGEX_SPLIT, searchWords);
        ParseResult parseResult = OutputParser.parseSingleOutput(parseInstructions, commandOutput);
        if (!parseResult.getSearchWord().matches(SEARCH_WORD_INTERFACE_SPEED)) {
            throw new IOException("Failed to parse interface speed output.");
        }
        return parseResult;
    }

    protected void testPingResponse(ArrayList<ParseResult> parseResults) {
        try {
            ArrayList<String> commandOutput = commandExecutor.executeCommand(PING_NET_COMMAND);
            parseResults.add(parsePingResponse(commandOutput));
        } catch (ExecuteException e) {
            logger.log(Level.WARNING, "Failed to execute ping6");
        } catch (IOException e) {
            logger.log(Level.WARNING, e.getMessage());
        }
    }

    protected ParseResult parsePingResponse(ArrayList<String> commandOutput) throws IOException {
        ArrayList<String> searchWords = new ArrayList<>(Arrays.asList(PING_SEARCH_WORD));
        ParseInstructions parseInstructions = new ParseInstructions(PING_SEARCH_ELEMENT_INDEX, PING_RETURN_ELEMENT_INDEX, PING_SPLIT_REGEX_STRING, searchWords);
        ParseResult parseResult = OutputParser.parseSingleOutput(parseInstructions, commandOutput);
        if (!parseResult.getSearchWord().matches(PING_SEARCH_WORD)) {
            throw new IOException("Failed to parse ping output.");
        }
        return parseResult;
    }

    protected void updateHardwareInfoWithNet(ArrayList<ParseResult> parseResults) {
        hardwareInfo.setIpv6Interface(false);
        hardwareInfo.setIpv4Interface(false);
        for (ParseResult parseResult : parseResults) {
            switch (parseResult.getSearchWord()) {
                case SEARCH_WORD_INTERFACE_IP4:
                    hardwareInfo.setIpv4Interface(true);
                    break;
                case SEARCH_WORD_INTERFACE_IPV6:
                    hardwareInfo.setIpv6Interface(true);
                    break;
                case SEARCH_WORD_INTERFACE_SPEED:
                    double speed = convertInterfaceSpeed(parseResult.getValue());
                    hardwareInfo.setInterfaceSpeedMbs(speed);
                    break;
                case PING_SEARCH_WORD:
                    setIpv6Connectivity(parseResult);
                    break;
                default:
                    if (parseResult.getSearchWord().matches(SEARCH_WORD_INTERFACE_NAME)) break;
                    throw new RuntimeException("Invalid ParseResult search word: " + parseResult.getSearchWord());
            }
        }
    }

    protected double convertInterfaceSpeed(String speed) {
        return Double.parseDouble(speed.replaceAll("[^\\d.]", ""));
    }

    protected void setIpv6Connectivity(ParseResult parseResult) {
        String pingResponse = parseResult.getValue();
        String packetLoss = pingResponse.replaceAll("[^\\d.]", "");
        if (packetLoss.equals("")) return;
        if (Double.parseDouble(packetLoss) > 99) return;
        hardwareInfo.setIpv6Connection(true);
    }

}
