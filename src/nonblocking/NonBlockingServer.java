package nonblocking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;

public class NonBlockingServer {

 
/**
 * Simple Java non-blocking NIO web server.
 *
 * @author md_5
 */
public class WebServer implements Runnable {
 
    private Charset charset = Charset.forName("UTF-8");
    private CharsetEncoder encoder = charset.newEncoder();
    private Selector selector = Selector.open();
    private ServerSocketChannel server = ServerSocketChannel.open();
    private boolean isRunning = true;
    private boolean debug = true;
 
    /**
     * Create a new server and immediately binds it.
     *
     * @param address the address to bind on
     * @throws IOException if there are any errors creating the server.
     */
    protected WebServer(InetSocketAddress address) throws IOException {
        server.socket().bind(address);
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
    }
 
    /**
     * Core run method. This is not a thread safe method, however it is non
     * blocking. If an exception is encountered it will be thrown wrapped in a
     * RuntimeException, and the server will automatically be {@link #shutDown}
     */
    @Override
    public final void run() {
        if (isRunning) {
            try { //@@ This try block will throw a runtime exception, and if it occurs the server will be shutdown. 
                selector.selectNow(); //@@ Select now doesn't block, and it returns immediately which ever channels are ready.
                Iterator<SelectionKey> i = selector.selectedKeys().iterator();  //@@ You can Iterate the selectionkey that is set to access the ready channels.
                while (i.hasNext()) { //@@ This while loop will iterate through the collection of selector keys
                    SelectionKey key = i.next(); //@@ Sets the value of key to the current selector selectedkey iterator.
                    i.remove(); //@@ the remove() method is called because if not the current channel will never become ready again. The selector readds it to the selectedkey set again once removed.
                    if (!key.isValid()) { //@@ Tells whether or not this key is valid.
                        continue; //@@ Will re-execute the loop containing (i.hasNext()) above.
                    }
                    try {//@@ Exception is encountered it will throw a RuntimeException.
                        // get a new connection
                        if (key.isAcceptable()) { //@@ Tests whether this key's channel is ready to accept a new socket connection.
                            // accept them
                            SocketChannel client = server.accept(); //@@ a connection was accepted by a ServerSocketChannel.
                            // non blocking please
                            client.configureBlocking(false); //@@ The client channel must be non-blocking in order to be used in a non-blocking to be used with a Selector.
                            // show out intentions
                            client.register(selector, SelectionKey.OP_READ);//@@ This is the client channel being registered with the selector. The second parameter of the register method indicates which event you are interested in listening for in the channel. In this case it is Read.
                            // read from the connection
                        } else if (key.isReadable()) { //@@ a channel is ready for reading
                            //  get the client
                            SocketChannel client = (SocketChannel) key.channel();  //@@ Returns the channel for which this key was created
                            // get the session
                            HTTPSession session = (HTTPSession) key.attachment(); //@@ Retrieves the current attachment.						
                            // create it if it doesn't exist
                            if (session == null) { 
                                session = new HTTPSession(client); //@@ set HTTPSession with local class HTTPSession
                                key.attach(session); //@@ Attaches the given object to this key. In this case the client SocketChannel
                            }
                            // get more data
                            session.readData(); //@@ Calls the readData() Method on session.
                            // decode the message
                            String line; //@@ Declare a String Variable.
                            while ((line = session.readLine()) != null) {
                                // check if we have got everything
                                if (line.isEmpty()) { //@@ line Evaluates to empty since it was not initialized with a string value
				    HTTPRequest request = new HTTPRequest(session.readLines.toString()); //@@ Creates a new HTTPRequest
                                    session.sendResponse(handle(session, request)); //@@ sendResponse method call with an parameter of the HTTPRequest return value of the handle method.
                                    session.close(); //@@ Closes the current session
                                }
                            }
                        }
                    } catch (Exception ex) { //@@ Error handling here
                        System.err.println("Error handling client: " + key.channel()); //@@ Prints the SelectionKey that had the error
                        if (debug) {
                            ex.printStackTrace(); //@@ This method prints the throwale and its backtrace to the standard error stream
                        } else {
                            System.err.println(ex); //@@Prints the error
                            System.err.println("\tat " + ex.getStackTrace()[0]); //@@ This method prints the stack element at element 0
                        }
                        if (key.attachment() instanceof HTTPSession) { //@@ key.attachment() retrieves the current attachment and if its an instance of HTTPSession
                            ((HTTPSession) key.attachment()).close(); //@@ The current attachment closes.
                        }
                    }
                }
            } catch (IOException ex) {
                // call it quits
                shutdown(); //@@ Quits
                // throw it as a runtime exception so that Bukkit can handle it
                throw new RuntimeException(ex); 
            }
        }
    }
 
    /**
     * Handle a web request.
     *
     * @param session the entire http session
     * @return the handled request
     */
    protected HTTPResponse handle(HTTPSession session, HTTPRequest request) throws IOException {
        HTTPResponse response = new HTTPResponse();
        response.setContent("I liek cates".getBytes());
        return response;
    }
 
    /**
     * Shutdown this server, preventing it from handling any more requests.
     */
    public final void shutdown() {
        isRunning = false;
        try {
            selector.close();
            server.close();
        } catch (IOException ex) {
            // do nothing, its game over
        }
    }
 
    /**
     * The HTTPSession class serves to maintain communication for the
     * SocketChannel by offering ways to write to the channel as well
     * as read from the SocketChannel. This class also serves to allow
     * the ability to close the SocketChannel.
     */ 
    public final class HTTPSession {
 
        /**
         * Variables for this class as labeled below.
         * Simply, the class creates storage for a SocketChannel,
         * ByteBuffer, StringBuilder, and integer.
         */ 
        private final SocketChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(2048);
        private final StringBuilder readLines = new StringBuilder();
        private int mark = 0;
 
        /**
         * The constructor requires a parameter of a SocketChannel.
         * The SocketChannel will be stored in the variable: channel.
         */
        public HTTPSession(SocketChannel channel) {
            this.channel = channel;
        }
 
        /**
         * The readLine method, of type String, takes the buffer and begins
         * to take the data it has stored from 'readData()'. It will take each byte, cast it as a
         * char, and place it into a temporary StringBuilder. This char placement
         * continues until there is no data left in the buffer. Once this is done,
         * the position in the buffer is stored in 'mark' and the data in sb
         * is added to the StringBuilder 'readLines' outside this method.
         * If there is nothing to return, the method will end up at the end where
         * it returns null.
         */
        public String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int l = -1;
            while (buffer.hasRemaining()) {
                char c = (char) buffer.get();
                sb.append(c);
                if (c == '\n' && l == '\r') {
                    // mark our position
                    mark = buffer.position();
                    // append to the total
                    readLines.append(sb);
                    // return with no line separators
                    return sb.substring(0, sb.length() - 2);
                }
                l = c;
            }
            return null;
        }
 
       /**
         * This method will serve the purpose of storing data from the stream
         * into the ByteBuffer so it can be handled in the readLine method.
         * This method starts off by first placing a limit on the buffer, which
         * is set to buffer capacity. Next the ByteByffer 'buffer' gets a sequence
         * bytes from the SocketChannel, 'channel'. An integer variable 'read'
         * exists to check if there was an error in the byte reading. If an
         * error is found (via -1) then we throw an exception.
         * The buffer.flip() serves to set the limit to the size of the amount
         * of data stored, then sets the buffer position to zero so it can be read
         * from the beginning later. The buffer's position is then stored in
         * 'mark'.
         */
        public void readData() throws IOException {
            buffer.limit(buffer.capacity());
            int read = channel.read(buffer);
            if (read == -1) {
                throw new IOException("End of stream");
            }
            buffer.flip();
            buffer.position(mark);
        }
        
        /**
         * This method serves to add data to the channelSocket. It will
         * take the parameter of a String, 'line' then put it through
         * a charBuffer, which is put through an encoder, so it can be written
         * into the socketChannel properly.
         */
        private void writeLine(String line) throws IOException {
            channel.write(encoder.encode(CharBuffer.wrap(line + "\r\n")));
        }
        
        /**
         * This method aims to send off a Response to an HTTP Request. It first
         * begins by tagging the response with it's default headers. (Determined in
         * the HTTPResponse class)
         * It then begins to write a message in the response to be sent out.
         * This info contains a version, responseCode, and the response reason.
         * Once the message is formed, a loop begins, (and ends when Map key's
         * header matchs the reponse's header's key) that begins to write to the
         * channel, via the writeLine() method, while seperating with colons.
         * Once the message is written, it then begins to write to the channel the
         * response data.
         */
        public void sendResponse(HTTPResponse response) {
            response.addDefaultHeaders();
            try {
                writeLine(response.version + " " + response.responseCode + " " + response.responseReason);
                for (Map.Entry<String, String> header : response.headers.entrySet()) {
                    writeLine(header.getKey() + ": " + header.getValue());
                }
                writeLine("");
                channel.write(ByteBuffer.wrap(response.content));
            } catch (IOException ex) {
                // slow silently
            }
        }
 
        /**
         *This method is simple, it closes the channel down.
         */
        public void close() {
            try {
                channel.close();
            } catch (IOException ex) {
            }
        }
    }
 
    public class HTTPRequest {
        //new declaration of strings
        private final String raw;
        private String method;  
        private String location;
        private String version;
        //new map...mapping keys to values
        private Map<String, String> headers = new HashMap<String, String>();
 
        //taking input and placing it into the string raw in this class
        public HTTPRequest(String raw) {
            this.raw = raw;
            parse();
        }
 
        
        private void parse() {
            // parse the first line
            //breaking down a string
            StringTokenizer tokenizer = new StringTokenizer(raw);
            method = tokenizer.nextToken().toUpperCase();
            location = tokenizer.nextToken();
            version = tokenizer.nextToken();
            // parse the headers
            String[] lines = raw.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String[] keyVal = lines[i].split(":", 2);
                headers.put(keyVal[0], keyVal[1]);
            }
        }
        
        //returning the string token for method
        public String getMethod() {
            return method;
        }
 
        //returning the string token for location
        public String getLocation() {
            return location;
        }
 
        //returning the values in the header
        public String getHead(String key) {
            return headers.get(key);
        }
    }
 
    public class HTTPResponse {
		//Class variables to be used by this class only.
        private String version = "HTTP/1.1";
        private int responseCode = 200;
        private String responseReason = "OK";
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        private byte[] content;
		//Creates a default header.
        private void addDefaultHeaders() {
            headers.put("Date", new Date().toString());
            headers.put("Server", "Java NIO Webserver by md_5");
            headers.put("Connection", "close");
            headers.put("Content-Length", Integer.toString(content.length));
        }
		//Returns the responce code class vairable.
        public int getResponseCode() {
            return responseCode;
        }
		//Returns the get responce reason class vairable.
        public String getResponseReason() {
            return responseReason;
        }
		//Returns the header class vairable.
        public String getHeader(String header) {
            return headers.get(header);
        }
		//Returns the cotent class vairable.
        public byte[] getContent() {
            return content;
        }
		//Sets the responce code class vairable as the input.
        public void setResponseCode(int responseCode) {
            this.responseCode = responseCode;
        }
		//Sets the responce reason class vairable as the input.
        public void setResponseReason(String responseReason) {
            this.responseReason = responseReason;
        }
		//Sets the content class vairable as the input.
        public void setContent(byte[] content) {
            this.content = content;
        }
		//Sets the header class vairable as the input.
        public void setHeader(String key, String value) {
            headers.put(key, value);
        }
    }
	//Main function to start the server on socket 5555.
    public void main(String[] args) throws Exception {
        WebServer server = new WebServer(new InetSocketAddress(5555));
        while (true) {
            server.run();
            Thread.sleep(100);
        }
    }
}
}
//[/i]
