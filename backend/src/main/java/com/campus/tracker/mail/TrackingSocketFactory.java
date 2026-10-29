package com.campus.tracker.mail;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

/** Closing the raw socket also interrupts a STARTTLS wrapper without taking SMTPTransport's monitor. */
final class TrackingSocketFactory extends SocketFactory {
    private final ThreadLocal<Attempt> current = new ThreadLocal<>();
    void begin(Attempt attempt) { current.set(attempt); }
    void end() { current.remove(); }
    static final class Attempt {
        private final Set<Socket> sockets = new HashSet<>();
        private boolean closed;
        synchronized Socket add(Socket socket) throws IOException {
            if(closed) { socket.close(); throw new IOException("SMTP deadline reached"); }
            sockets.add(socket); return socket;
        }
        synchronized void close() {
            closed = true;
            for(Socket socket : sockets) try { socket.close(); } catch(IOException ignored) { }
            sockets.clear();
        }
    }
    @Override public Socket createSocket() throws IOException {
        Attempt attempt = current.get();
        if(attempt == null) throw new IOException("SMTP transport outside bounded send");
        return attempt.add(new Socket());
    }
    private Socket connect(InetSocketAddress remote, InetSocketAddress local) throws IOException {
        Socket socket = createSocket();
        if(local != null) socket.bind(local);
        socket.connect(remote, 10000); return socket;
    }
    @Override public Socket createSocket(String host, int port) throws IOException { return connect(new InetSocketAddress(host,port),null); }
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException {
        return connect(new InetSocketAddress(host,port),new InetSocketAddress(local,localPort));
    }
    @Override public Socket createSocket(InetAddress address,int port) throws IOException { return connect(new InetSocketAddress(address,port),null); }
    @Override public Socket createSocket(InetAddress address,int port,InetAddress local,int localPort) throws IOException {
        return connect(new InetSocketAddress(address,port),new InetSocketAddress(local,localPort));
    }
}
