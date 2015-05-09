package com.us.openserver;

import java.io.*;
import java.net.Socket;
import java.util.*;

import com.us.openserver.configuration.*;
import com.us.openserver.protocols.*;
import com.us.openserver.util.*;

public class Session implements Runnable
{
    public boolean IsAuthenticated;
    public Date LastActivityAt;
    public String UserName;

    private boolean isClosed;
    private HashMap<Integer, IProtocol> protocolImplementations = new HashMap<Integer, IProtocol>();
    private Client client;
    private ILogger logger;
    private InputStream is;
    private Object syncObject = new Object();
    private OutputStream os;    
    private String address;    
    private int id;
    private static int sessionId;    
    
    public Session(Client client, Socket socket, String address) throws IOException
    {
    	this.client = client;
	    this.is = socket.getInputStream();
	    this.os = socket.getOutputStream();
	    this.address = address;
	    this.logger = client.getLogger();
	    id = ++sessionId;
	}

    public void close()
    {
    	if (!isClosed)
    	{
    		isClosed = true;
    		
	        synchronized (protocolImplementations)
	        {
	            for (IProtocol pl : protocolImplementations.values())
	                pl.close();
	
	            protocolImplementations.clear();
	        }
	
	        try { is.close(); } catch (IOException ex) {}
	        try { os.close(); } catch (IOException ex) {}	        
    	}
    }
    
    public void dispose()
    {    
    	synchronized (syncObject)
        {
	    	if (!isClosed)
	    	{
	    		isClosed = true;
	    		
		    	synchronized (protocolImplementations)
		        {
		            for (IProtocol pl : protocolImplementations.values())
		                pl.dispose();
		
		            protocolImplementations.clear();
		        }
		    	
		    	try { is.close(); } catch (IOException ex) {}
		        try { os.close(); } catch (IOException ex) {}
	    	}
        }
    }
    
    public void beginRead()
    {
    	new Thread(this, "SessionThread").start();    	
    }

    public void connectionLost(Exception ex)
    {
    	boolean tmp;
    	synchronized (syncObject)
    	{
    		tmp = isClosed;
    		if (!isClosed)
    		{
	    		log(Level.Critical, String.format("The socket connection has been lost.  %1$s", ex.getMessage()));
	    		dispose();
    		}
    	}
    	if (!tmp)
    		client.onConnectionLost(ex);
    }
    
    public String getAddress() { return address; }
    
    public IProtocol initialize(int protocolId) throws Exception
    {
        IProtocol p = null;
        synchronized (protocolImplementations)
        {
            if (!protocolImplementations.containsKey(protocolId))
            {
                if (!ProtocolConfigurations.ContainsKey(protocolId))
                    throw new Exception("Invalid or unsupported protocol.");

                ProtocolConfiguration plc = ProtocolConfigurations.Get(protocolId);

                Class<?> cls = Class.forName(plc.getClassPath());
                p = (IProtocol)cls.newInstance();

                protocolImplementations.put(protocolId, p);

                log(Level.Debug, String.format("Initializing protocol %1$s...", protocolId));
                p.initialize(this, plc);
                LastActivityAt = new Date();
            }
            else
                p = protocolImplementations.get(protocolId);
        }
        return p;
    }

    public void onPacketReceived(BinaryReader br) throws Exception
    {
        int protocolId = br.readUInt16();

        IProtocol p;
        synchronized (protocolImplementations)
        {
            if (protocolImplementations.containsKey(protocolId))
                p = protocolImplementations.get(protocolId);
            else
            {
                if (!ProtocolConfigurations.ContainsKey(protocolId))
                    throw new Exception("Invalid or unsupported protocol.");

                ProtocolConfiguration plc = ProtocolConfigurations.Get(protocolId);

                Class<?> cls = Class.forName(plc.getClassPath());
                p = (IProtocol)cls.newInstance();
                if (p == null)
                    throw new Exception("Unable to create protocol layer.  Class not found.  Class: " + plc.getClassPath());

                if (!IsAuthenticated && !(p instanceof AuthenticationProtocolBase))
                    throw new Exception("Unable to add protocol layer.  Access Denied.  Class: " + plc.getClassPath());

                protocolImplementations.put(protocolId, p);

                log(Level.Debug, "Initializing protocol " + protocolId + "...");
                p.initialize(this, plc);
            }
            LastActivityAt = new Date();
        }
        p.onPacketReceived(br);
    }
    
    public void run()
    {
        int available = 0;
        try
            {
            byte readState = PacketReadTypes.Header;
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            int payloadLength  = 0;
            int payloadPosition = 0;
            int position;

            while ((available = is.read(buffer)) != -1)
            {
                position = 0;

                while (available > 0)
                {
                    int lengthToRead;
                    if (readState == PacketReadTypes.Header)
                    {
                        lengthToRead = packet.size() + available >= SessionLayerProtocol.HEADER_LENGTH ?
                                SessionLayerProtocol.HEADER_LENGTH - packet.size() :
                                available;

                        packet.write(buffer, position, lengthToRead);
                        position += lengthToRead;
                        available -= lengthToRead;

                        if (packet.size() >= SessionLayerProtocol.HEADER_LENGTH)
                            readState = PacketReadTypes.HeaderComplete;
                    }

                    if (readState == PacketReadTypes.HeaderComplete)
                    {
                        BinaryReader br = new BinaryReader(packet.toByteArray());
						try
						{
	                        int protocolId = br.readUInt16();
	                        if (protocolId != SessionLayerProtocol.PROTOCAL_IDENTIFIER)
	                            throw new Exception("Invalid or unsupported protocol.");
	
	                        payloadLength = br.readInt32();
                        }
                        finally {
                        	try {br.close();}catch (IOException ex) {}
                        }
                        
                        readState = PacketReadTypes.Payload;
                    }

                    if (readState == PacketReadTypes.Payload)
                    {
                        lengthToRead = available >= payloadLength - payloadPosition ?
                                payloadLength - payloadPosition :
                                available;

                        packet.write(buffer, position, lengthToRead);
                        position += lengthToRead;
                        available -= lengthToRead;
                        payloadPosition += lengthToRead;

                        if (packet.size() >= SessionLayerProtocol.HEADER_LENGTH + payloadLength)
                        {
                            if (logger.getLogPackets())
                            	log(Level.Debug, "RECV: " + toHexString(packet.toByteArray(), 0, packet.size()));

                            BinaryReader br = new BinaryReader(packet.toByteArray());
                            br.skip(SessionLayerProtocol.HEADER_LENGTH);

                            PacketHandler packetHandler = new PacketHandler(this, br);
                            packetHandler.execute();

                            readState = PacketReadTypes.Header;
                            packet = new ByteArrayOutputStream();
                            payloadLength  = 0;
                            payloadPosition = 0;
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
        	connectionLost(ex);
        }
    }
    
    public void send(byte[] buf) throws IOException
    {
        BinaryWriter bw = new BinaryWriter();
        try
        {
	        bw.writeUInt16(SessionLayerProtocol.PROTOCAL_IDENTIFIER);
	        bw.writeUInt(buf.length);
	        bw.write(buf);
	
	        buf = bw.toByteArray();
	        if (logger.getLogPackets())
	        	log(Level.Debug, "SEND: " + toHexString(buf, 0, buf.length));
	
	        synchronized (os)
	        {
	            bw.writeTo(os);
	        }
        }
        finally
        {
	        try
	        {
	        	bw.close();
	        }
	        catch (IOException ex)
	        {
	        }
        }
    }

    public void signalClose()
    {
        SessionCloser closer = new SessionCloser(this);
        closer.close();
    }
    
    private String toHexString(byte[] val, int position, int length)
    {
        if (val == null || val.length == 0)
            return "";

        StringBuilder s = new StringBuilder(val.length * 2);
        for (int i = position; i < position + length; i++)
        {
            String str = Integer.toHexString((int) val[i] & 0x000000FF);
            if (((int) val[i] & 0x000000FF) <= 15)
                str = "0" + str;

            if (i > position)
                str = " " + str;

            s.append(str);
        }

        return s.toString().toUpperCase();
    }
    
    public void log(Level level, String message)
    {
        logger.log(level, String.format("Session [%1$s %2$s] - %3$s", id, address, message));
    }

    public void log(Exception ex)
    {
        log(Level.Error, ex.getMessage());
    }
}