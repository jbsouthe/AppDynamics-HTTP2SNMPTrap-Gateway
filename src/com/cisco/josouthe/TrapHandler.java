package com.cisco.josouthe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.snmp4j.*;
import org.snmp4j.event.*;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultTcpTransportMapping;
import org.snmp4j.transport.DefaultUdpTransportMapping;

public class TrapHandler implements HttpHandler {
    private static final Logger logger = LogManager.getFormatterLogger(TrapHandler.class.getName());
    CommunityTarget target = null;
    String baseOID = ".1.3.6.1.4.1.40684.138."; //enterprises.appdynamics.events
    String trapOID = baseOID + "2"; //.EventNotification
    String baseDataOID = baseOID +"1.1."; //.eventsTable.eventEntry
    String eventIdOID = baseDataOID +"1"; //String
    String eventTypeOID = baseDataOID +"2"; //Integer table
    String guidOID = baseDataOID +"3"; //String
    String eventTypeKeyOID = baseDataOID +"4"; //String
    String eventTimeStampOID = baseDataOID +"5"; //String
    String displayNameOID = baseDataOID +"6"; //String
    String summaryMessageOID = baseDataOID +"7"; //String
    String eventMessageOID = baseDataOID +"8"; //String
    String applicationNameOID = baseDataOID +"9"; //String
    String applicationIdOID = baseDataOID +"10"; //Integer
    String tierNameOID = baseDataOID +"11"; //String
    String tierIdOID = baseDataOID +"12"; //Integer
    String nodeNameOID = baseDataOID +"13"; //String
    String nodeIdOID = baseDataOID +"14"; //Integer
    String databaseNameOID = baseDataOID +"15"; //String
    String databaseIdOID = baseDataOID +"16"; //Integer
    String severityOID = baseDataOID +"17"; //Integer Table 1-3
    String severityImageURLOID = baseDataOID +"18"; //String
    String accountNameOID = baseDataOID +"19"; //String
    String policyNameOID = baseDataOID +"20"; //String
    String actionNameOID = baseDataOID +"21"; //String
    String controllerURLOID = baseDataOID +"22"; //String
    String deepLinkURLOID = baseDataOID +"23"; //String
    String notesOID = baseDataOID +"24"; //String
    String eventTypeStringOID = baseDataOID +"25"; //String
    String eventSubTypeStringOID = baseDataOID +"26"; //String
    String machineNameListOID = baseDataOID +"27"; //String
    String ipAddressListOID = baseDataOID +"28"; //String

    String regex = "\\s*(.+):\"(.*)\"";
    Pattern pattern;

    public TrapHandler(String path) {
        this.target = new CommunityTarget();
        this.target.setRetries(2);
        this.target.setTimeout(3000);
        this.pattern = Pattern.compile(this.regex);
        logger.info("Initialized Trap Handler for context: "+ path);
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        logger.debug("Handling Trap forward request from: "+ httpExchange.getRemoteAddress().toString());
        String response = "Trap Successfully Forwarded"; //default to success
        int responseCode = 200;

        try {
            HashMap<String,String> paramsMap = new HashMap<>();
            ArrayList<String> receivers = new ArrayList<>();
            BufferedReader reader = new BufferedReader( new InputStreamReader(httpExchange.getRequestBody(),"utf-8"));
            int b;
            StringBuilder sb = new StringBuilder(512);
            while(( b = reader.read()) != -1 ) sb.append((char) b);
            //System.out.println("Finished reading request body: "+ sb.toString());
            Matcher matcher = pattern.matcher(sb.toString());
            while(matcher.find()) {
                if( matcher.group(1).equalsIgnoreCase("receiver")) {
                    receivers.add( matcher.group(2) );
                    logger.debug("Added snmp trap receiver: "+ matcher.group(2));
                } else if( matcher.group(1).equalsIgnoreCase("machineName") || matcher.group(1).equalsIgnoreCase("ipAddresses") ) {
                    String list = paramsMap.get(matcher.group(1));
                    paramsMap.put(matcher.group(1), (list != null? list +"," : "") + matcher.group(2));
                } else {
                    paramsMap.put(matcher.group(1), matcher.group(2));
                }
                logger.debug("Parser, key: "+ matcher.group(1) +" value: "+ matcher.group(2));
            }

            target.setCommunity(new OctetString( (paramsMap.get("Community") != null) ? paramsMap.get("Community") : "public") );
            //target.setAddress(GenericAddress.parse(paramsMap.get("Receiver")));
            switch( paramsMap.get("Version") ) {
                case "1": target.setVersion( SnmpConstants.version1 ); break;
                case "3": target.setVersion( SnmpConstants.version3 ); break;
                case "2": ;
                default: target.setVersion( SnmpConstants.version2c ); break;
            }
            if( target.getVersion() == SnmpConstants.version2c) {
                int count =0;
                for( String receiver : receivers ){
                    try {
                        logger.info("Sending trap to: "+ receiver + " From: "+ httpExchange.getRemoteAddress().toString()+ " Severity: "+ paramsMap.get("severity") +" Event Type: "+ paramsMap.get("eventType"));
                        sendSnmpV2Trap(httpExchange.getLocalAddress().getAddress().getHostAddress(), receiver, paramsMap);
                        count++;
                    } catch (Exception e) {
                        logger.warn("IOException trying to send a trap: " + e.getMessage(),e);
                    }
                }
                if( count == 0 ) {
                    response = "Trap not sent to any receivers successfully";
                    responseCode = 500;
                } else {
                    response = "Trap sent to "+ count +" receiver(s) successfully";
                    responseCode = 200;
                }
            }

            //httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            httpExchange.sendResponseHeaders(responseCode, response.getBytes().length);
            OutputStream os = httpExchange.getResponseBody();
            os.write(response.getBytes());
            logger.info("Sending Response: "+ response);
            os.close();
        } catch ( Exception e ) {
            logger.warn("Error in Trap Sending: "+ e.getMessage(),e );
        }
        logger.debug("Finished trap handle request");
    }

    private void sendSnmpV2Trap( String ipAddress, String receiver, HashMap<String,String> paramsMap ) throws IOException {
        //set the target:
        target.setAddress(GenericAddress.parse(receiver));

        // Create Transport Mapping
        TransportMapping transport = new DefaultUdpTransportMapping();
        if( receiver.startsWith("tcp:") ) transport = new DefaultTcpTransportMapping();
        transport.listen();

        // Create PDU for V2
        PDU pdu = new PDU();
        pdu.setType( PDU.NOTIFICATION);

        // map each key in the post request to an oid in the mib
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks()));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID(trapOID)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapAddress, new IpAddress(ipAddress)));
        //pdu.setErrorStatus(PDU.noError); //some options here PDU.noError is default
        //pdu.setType(PDU.TRAP);

        // variable binding for Enterprise Specific objects
        pdu.add(new VariableBinding(new OID(eventIdOID), new OctetString(paramsMap.get("eventId"))));
        pdu.add(new VariableBinding(new OID(eventTypeOID), new Integer32( mapEventTypeToInteger(paramsMap.get("eventType")))));
        pdu.add(new VariableBinding(new OID(guidOID), new OctetString(paramsMap.get("guid"))));
        pdu.add(new VariableBinding(new OID(eventTypeKeyOID), new OctetString(paramsMap.get("eventTypeKey"))));
        pdu.add(new VariableBinding(new OID(eventTimeStampOID), new OctetString(paramsMap.get("eventTimeStamp"))));
        pdu.add(new VariableBinding(new OID(displayNameOID), new OctetString(paramsMap.get("displayName"))));
        pdu.add(new VariableBinding(new OID(summaryMessageOID), new OctetString(paramsMap.get("summaryMessage"))));
        pdu.add(new VariableBinding(new OID(eventMessageOID), new OctetString(paramsMap.get("eventMessage"))));
        pdu.add(new VariableBinding(new OID(applicationNameOID), new OctetString(paramsMap.get("applicationName"))));
        pdu.add(new VariableBinding(new OID(applicationIdOID), new Integer32( getIntIfYouCan(paramsMap.get("applicationId")) )));
        pdu.add(new VariableBinding(new OID(tierNameOID), new OctetString(paramsMap.get("tierName"))));
        pdu.add(new VariableBinding(new OID(tierIdOID), new Integer32( getIntIfYouCan(paramsMap.get("tierId")))));
        pdu.add(new VariableBinding(new OID(nodeNameOID), new OctetString(paramsMap.get("nodeName"))));
        pdu.add(new VariableBinding(new OID(nodeIdOID), new Integer32( getIntIfYouCan(paramsMap.get("nodeId")))));
        pdu.add(new VariableBinding(new OID(databaseNameOID), new OctetString(paramsMap.get("databaseName"))));
        pdu.add(new VariableBinding(new OID(databaseIdOID), new Integer32( getIntIfYouCan(paramsMap.get("databaseId")))));
        pdu.add(new VariableBinding(new OID(severityOID), new Integer32( mapSeverityToInteger(paramsMap.get("severity")) )));
        /* we won't do this, it isn't appropriate, just playing
        switch (mapSeverityToInteger(paramsMap.get("severity")) ) {
            case 1: pdu.setErrorStatus(PDU.noError); break;
            case 2: pdu.setErrorStatus(PDU.genErr); break;
            case 3: pdu.setErrorStatus(PDU.genErr); break;
            default: pdu.setErrorStatus(PDU.wrongType);
        }
         */
        pdu.add(new VariableBinding(new OID(severityImageURLOID), new OctetString(paramsMap.get("severityImageURL"))));
        pdu.add(new VariableBinding(new OID(accountNameOID), new OctetString(paramsMap.get("accountName"))));
        pdu.add(new VariableBinding(new OID(policyNameOID), new OctetString(paramsMap.get("policyName"))));
        pdu.add(new VariableBinding(new OID(actionNameOID), new OctetString(paramsMap.get("actionName"))));
        pdu.add(new VariableBinding(new OID(controllerURLOID), new OctetString(paramsMap.get("controllerUrl"))));
        pdu.add(new VariableBinding(new OID(deepLinkURLOID), new OctetString(paramsMap.get("deepLink"))));
        pdu.add(new VariableBinding(new OID(notesOID), new OctetString(paramsMap.get("notes"))));
        pdu.add(new VariableBinding(new OID(eventTypeStringOID), new OctetString(paramsMap.get("severity"))));
        pdu.add(new VariableBinding(new OID(eventSubTypeStringOID), new OctetString(paramsMap.get("eventType"))));
        pdu.add(new VariableBinding(new OID(machineNameListOID), new OctetString(getStringIfYouCan(paramsMap.get("machineName")))));
        pdu.add(new VariableBinding(new OID(ipAddressListOID), new OctetString(getStringIfYouCan(paramsMap.get("ipAddresses")))));


        pdu.setType(PDU.NOTIFICATION);

        logger.debug("PDU: "+ pdu.toString());

        // Send the PDU
        Snmp snmp = new Snmp(transport);
        snmp.send(pdu, this.target);
        snmp.close();
    }

    private String getStringIfYouCan( String s ) {
        if( s == null ) return "";
        return s;
    }

    private int getIntIfYouCan(String number) {
        try {
            return Integer.parseInt(number);
        } catch (Exception e) { return -1; }
    }

    private int mapEventTypeToInteger(String eventType) {
        if( eventType == null || "".equals(eventType) ) return 13;
        //         HealthRuleViolationEvent (1),
        if( eventType.startsWith("POLICY_") ) return 1;
        //         AnomalyViolationEvent (2),
        if( eventType.startsWith("ANOMALY_")) return 2;
        //         SlowTransactionEvent (3),
        if( "SLOW".equals(eventType) || "VERY_SLOW".equals(eventType) ||  "STALL".equals(eventType) ) return 3;
        //         CodeProblemEvent (4),
        if( "DEADLOCK".equals(eventType) || "RESOURCE_POOL_LIMIT".equals(eventType) ) return 4;
        //         ApplicationChangeEvent (5),
        if( "APPLICATION_DEPLOYMENT".equals(eventType) || "APP_SERVER_RESTART".equals(eventType) || "APPLICATION_CONFIG_CHANGE".equals(eventType) ) return 5;
        //         ServerCrashEvent (6),
        if( eventType.endsWith("_CRASH") ) return 6;
        //         AppDynamicsConfigWarningEvent (7),
        if( "AGENT_CONFIGURATION_ERROR".equals(eventType) ) return 7;
        //         DiscoveryEvent (8),
        if( eventType.endsWith("_DISCOVERED") ) return 8;
        //         SyntheticPerformanceEvent(10),
        if( eventType.startsWith("EUM_CLOUD_SYNTHETIC_PERF_")) return 10;
        //         SyntheticAvailabilityEvent(9),
        if( eventType.startsWith("EUM_CLOUD_SYNTHETIC_")) return 9;
        //         MobileCrashEvent(11),
        if( eventType.startsWith("MOBILE_")) return 11;
        //         ErrorEvent(12),
        if( eventType.contains("ERROR") ) return 12;
        logger.info("TODO Convert event to int: "+ eventType);
        return 13; //unknown event
    }

    private int mapSeverityToInteger(String severity) {
        switch (severity) {
            case "INFO": return 1;
            case "WARNING": return 2;
            case "ERROR": return 3;
        }
        return 1;
    }
}
