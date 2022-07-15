package com.cisco.josouthe;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class Main {
    private static String internalConfigFile = "main/resources/config.properties";
    private static final Logger logger = LogManager.getFormatterLogger();
    public Main( String configFileName ) {
        logger.info("System starting with configuration: " + configFileName );

        Properties props = new Properties();
        File configFile = new File(configFileName);
        InputStream is = null;
        if( configFile.canRead() ) {
            try {
                is = new FileInputStream(configFile);
            } catch (FileNotFoundException e) {
                logger.error("Config file not found! Exception: "+e);
            }
        } else {
            logger.warn("Config file not found: "+ configFileName +" attempting to load from jar internally");
            URL configFileURL = getClass().getClassLoader().getResource(internalConfigFile);
            logger.info("Config file URL: " + configFileURL);
            is = getClass().getClassLoader().getResourceAsStream(internalConfigFile);
        }
        if(is == null) {
            logger.fatal("UNABLE TO START, no configuration found!");
            System.exit(1);
        }
        try {
            props.load(is);
        } catch (IOException e) {
            logger.error("Error loading configuration: "+ configFileName +" Exception: "+ e.getMessage());
            System.exit(1);
        }

        boolean useSSL = false;
        if( props.getProperty("useSSL","false").equalsIgnoreCase("true") )
            useSSL=true;

        boolean useClientAuth = false;
        if( props.getProperty("authRequired","false").equalsIgnoreCase("true") )
            useClientAuth=true;
        logger.info("Starting server with settings: useSSL=%s authRequired=%s",useSSL,useClientAuth);
        try {
            // setup the socket address
            InetSocketAddress address = new InetSocketAddress( Integer.parseInt(props.getProperty("serverPort","8000")) );

            if( useSSL ) {
                logger.info("Starting HTTPS Server...");
                // initialise the HTTPS server
                HttpsServer httpsServer = HttpsServer.create(address, 0);
                SSLContext sslContext = SSLContext.getInstance(props.getProperty("sslContext", "TLS"));

                // initialise the keystore
                char[] password = props.getProperty("keystorePassword", "password").toCharArray();
                KeyStore ks = KeyStore.getInstance(props.getProperty("keystoreInstance", "JKS"));
                //keytool -genkeypair -keyalg RSA -alias selfsigned -keystore testkey.jks -storepass password -validity 360 -keysize 2048
                FileInputStream fis = new FileInputStream(props.getProperty("keystoreFile", "testkey.jks"));
                ks.load(fis, password);

                // setup the key manager factory
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(props.getProperty("keyManagerFactory", "SunX509"));
                kmf.init(ks, password);

                // setup the trust manager factory
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(props.getProperty("trustManagerFactory", "SunX509"));
                tmf.init(ks);

                // setup the HTTPS context and parameters
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                boolean finalUseClientAuth = useClientAuth;
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    public void configure(HttpsParameters params) {
                        try {
                            // initialise the SSL context
                            SSLContext context = getSSLContext();
                            SSLEngine engine = context.createSSLEngine();
                            params.setNeedClientAuth(finalUseClientAuth);
                            params.setCipherSuites(engine.getEnabledCipherSuites());
                            params.setProtocols(engine.getEnabledProtocols());

                            // Set the SSL parameters
                            SSLParameters sslParameters = context.getSupportedSSLParameters();
                            params.setSSLParameters(sslParameters);

                        } catch (Exception ex) {
                            logger.error("Failed to create HTTPS port",ex);
                        }
                    }
                });
                HttpContext context = httpsServer.createContext(props.getProperty("appdynamics-trap-uri","/trap"), new TrapHandler(props.getProperty("appdynamics-trap-uri","/trap")));
                if( useClientAuth )
                    context.setAuthenticator(new BasicAuthenticator("trap") {
                         @Override
                         public boolean checkCredentials(String user, String pass) {
                             return user.equals( props.getProperty("authUser")) && pass.equals(props.getProperty("authPassword"));
                         }
                     }
                    );
                httpsServer.createContext(props.getProperty("appdynamics-mib-uri","/mib"), new SendFileHandler(props.getProperty("appdynamics-mib-uri","/mib"), props.getProperty("appdynamics-mib-file","main/resources/AppDynamics-Trap.mib")));
                httpsServer.createContext(props.getProperty("appdynamics-action-uri","/action"), new SendFileHandler(props.getProperty("appdynamics-action-uri","/action"), props.getProperty("appdynamics-action-file","main/resources/AppDynamics-custom-action-payload.txt")));
                httpsServer.setExecutor(new ThreadPoolExecutor(4, Integer.parseInt(props.getProperty("threadPoolMaxSize", "8")), Integer.parseInt(props.getProperty("threadPoolKeepAliveSeconds", "30")), TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(Integer.parseInt(props.getProperty("threadPoolCapacity", "100")))));
                httpsServer.start();
                logger.info("Server Started listening on "+ address.toString());
            } else {
                logger.info("Starting HTTP Server (without SSL) ...");
                HttpServer httpServer = HttpServer.create( address, 0);
                HttpContext context = httpServer.createContext(props.getProperty("appdynamics-trap-uri","/trap"), new TrapHandler(props.getProperty("appdynamics-trap-uri","/trap")));
                if( useClientAuth )
                    context.setAuthenticator(new BasicAuthenticator("trap") {
                         @Override
                         public boolean checkCredentials(String user, String pass) {
                             return user.equals( props.getProperty("authUser")) && pass.equals(props.getProperty("authPassword"));
                         }
                     }
                    );
                httpServer.createContext(props.getProperty("appdynamics-mib-uri","/mib"), new SendFileHandler(props.getProperty("appdynamics-mib-uri","/mib"), props.getProperty("appdynamics-mib-file","main/resources/AppDynamics-Trap.mib")));
                httpServer.createContext(props.getProperty("appdynamics-action-uri","/action"), new SendFileHandler(props.getProperty("appdynamics-action-uri","/action"), props.getProperty("appdynamics-action-file","main/resources/AppDynamics-custom-action-payload.txt")));
                httpServer.setExecutor(new ThreadPoolExecutor(4, Integer.parseInt(props.getProperty("threadPoolMaxSize", "8")), Integer.parseInt(props.getProperty("threadPoolKeepAliveSeconds", "30")), TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(Integer.parseInt(props.getProperty("threadPoolCapacity", "100")))));
                httpServer.start();
                logger.info("Server Started listening on "+ address.toString());
            }

        } catch (Exception exception) {
            logger.error("Failed to create web server on port " + props.getProperty("serverPort","8000") + " of localhost", exception);
            return;
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        String configFileName = internalConfigFile;
        if (args.length > 0) configFileName = args[0];
        Main main = new Main(configFileName);
    }
}
