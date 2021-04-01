package com.cisco.josouthe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SendFileHandler implements HttpHandler {
    private static final Logger logger = LogManager.getLogger(SendFileHandler.class);
    String response = "";
    Boolean isError = false;
    String path;
    String filename;

    public SendFileHandler(String path, String filename) {
        logger.info("Initializing File Handler for: "+ filename + " for context: "+ path);
        this.path = path;
        this.filename = filename;
        try {
            //response = new String(Files.readAllBytes(Paths.get(getClass().getResource(filename).toURI())));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            // StandardCharsets.UTF_8.name() > JDK 7
            response = result.toString("UTF-8");
            isError = false;
            logger.debug("Initialized file handler for "+ filename +" Response will be an error? "+ isError);
        } catch (Exception e) {
            logger.warn("Could not initialize a File Handler for "+ filename +" Exception: "+ e.getMessage(), e);
            response = "Error loading file " + filename + " from jar, sorry, can't send this to you right now: " + e.getMessage();
            logger.debug(response);
            isError = true;
        }

    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        /*
        1.3.6.1.4.1.25797 southerland-consulting (will use appdynamics instead)
        1.3.6.1.4.1.40684 appdynamics
         */
        logger.debug("Begin Handling request for file: "+ filename);
        try {
            //t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if (isError) {
                t.sendResponseHeaders(404, response.getBytes().length);
            } else {
                t.sendResponseHeaders(200, response.getBytes().length);
            }
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
            logger.info("Request for file: "+ filename +" from: "+ t.getRemoteAddress().toString());
        }catch (Exception e) {
            logger.warn("Error in handle of request: "+ e.getMessage(),e);
        }
        logger.debug("Finish Handling request for file: "+ filename);
    }
}
