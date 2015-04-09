package com.example.wificastapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;

import android.support.v7.app.ActionBarActivity;
import android.annotation.TargetApi;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class SenderConnection extends ListActivity {
	DhcpInfo d;
	String FileName, FilePath;    
	int DatagramSize = 1000;
    int WCHeaderSize = 7;
    InetAddress WCMulticastAddress;
	int WCSourcePort = 4567;
	int WCSinkPort = 4576;    
	String Filler = new String("wc"); // To fill EOT and conn packets with some data.
	long SocketTimeout = 1000;
	
	private MulticastSocket WCSocketSource = null;
	private Vector<SinkStats> sinkList;
	private SinkArrayAdapter adapter;
	private SinkStats s ;
    // Retransmission Variables 
    private Vector<Integer> retransmissionQueue;

    // WifiCast Protocol Variables: 
    private long TransWindow = 900;  // Time Window(ms) for transmission of first-time packets
    private long RetransWindow = 100; // Time Window(ms) for retransmission of packets. 
	private long ConnPhaseTimeout = 10000;  // Connection Phase Timeout. Default is 10 seconds. Configurable.
	private long MetadataInterval = 1000; // Intervals in which metadata is sent.
	private int RetryCount = 10;   //  retries for redundancy
	private int QuietEOTRounds = 300; // Number of consecutive EOT rounds which do not receive a SNACK, indicating End of File Transfer.
	private long EOTRoundTime ; // Time Interval of EOT rounds in the end-phase after processing of requests.[Optional]
	private int snackRoundNo = 50;  // Snack response (Retransmitted) packets in a round
	private int transRoundNo = 150;  // Data packets sent in a round
	
	private long interPacketDelay ;  
	private Integer currEOTRound = new Integer(0);
	private int currEOTNoSource;
	private float wifiRate = 1;			// Wireless Interface Rate in Mbps.
	private double WCRate = 0.8;      // Default 1 Mbps Rate. Configurable.
	
	private int currPacketNoSource;  // Source current packet no.
	private TextView currPacketNoDisp ; 
	
	private Thread listenThread;
	private Thread metadataThread;
	private Thread feedbackThread;
	
    private Vector<byte[]> fileBuffer;
	private int fileBufferSize;
	private File file;
	private boolean fileTransferComplete = false;
	private Boolean fileTransferStart = false;
	
	 private class DownloadFilesTask extends AsyncTask<Integer, SinkArrayAdapter, Long> {

		@Override
		protected Long doInBackground(Integer... arg0) {
			//Log.d(ACTIVITY_SERVICE, "Listening to snack");
			while(fileTransferStart) {
				try{
					Thread.sleep(500);
				} catch(InterruptedException e) {}
			}
			byte[] buf = new byte[1000];
			DatagramPacket snack = new DatagramPacket(buf, buf.length);
			try {
				WCSocketSource.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}

			while(!Thread.currentThread().isInterrupted()) {
				//Log.d(ACTIVITY_SERVICE, "Listening to Snack here man");
				try {
					WCSocketSource.receive(snack); 
					synchronized(currEOTRound) {
						currEOTRound = 0;
					}
					//Log.d(ACTIVITY_SERVICE, "Got some Snack here man");

					String received = new String(snack.getData(), 0, snack.getLength());
					MainActivity.WCPacket snackPacket = new MainActivity.WCPacket();
					snackPacket.parse(snack);

					if(snackPacket.type == 's') {
						// Add SNACK packet no requested in retransmission Queue.
						String[] nos = new String(snackPacket.dataStr).split("&");
						Log.d(ACTIVITY_SERVICE, snackPacket.dataStr);
						// First no is data is number of lost packets. Update count.
						for(int i = 0; i < sinkList.size(); ++i) {
							if(sinkList.get(i).addr.getHostAddress().equals(
									snack.getAddress().getHostAddress()) && sinkList.get(i).getLostCount() != Integer.parseInt(nos[0])) {

								sinkList.get(i).updateCount(Integer.parseInt(nos[0]));
								publishProgress(adapter);
//								runOnUiThread(new Runnable() {
//									@Override
//									public void run() {
//										Log.d(ACTIVITY_SERVICE, "Its here please");
//										adapter.notifyDataSetChanged();
//
//									}
//								});
							}
						}

						// Add SNACK packet no requested in retransmission Queue.
						for(int i = 1; i < nos.length; ++i) {
							if(retransmissionQueue.contains(new Integer(nos[i])) == false) {
								retransmissionQueue.add(new Integer(nos[i]));
							}
						}
					}
					else if(snackPacket.type == 'a') {
						String[] fields = new String(snackPacket.dataStr).split("&");
						// Ack recvd from Sink
						for(int i = 0; i < sinkList.size(); ++i) {
							if(sinkList.get(i).addr.getHostAddress().equals(
									snack.getAddress().getHostAddress()) && !sinkList.get(i).isFileTransferComplete()) {
								sinkList.get(i).setFileTransferComplete();
								sinkList.get(i).setFileTransferTime(Integer.parseInt(fields[0]));
							}
						}
						boolean transferflag = true;
						for(int i = 0; i < sinkList.size(); ++i) {
							if( !sinkList.get(i).isFileTransferComplete()) {
								transferflag = false;
							}
						}
						if(transferflag == true) {
							fileTransferComplete = true;
						}
					}
				} catch(IOException e) {}	      
			}
			return (long) 0;
		}
		
		protected void onProgressUpdate(SinkArrayAdapter... progress) {
			Log.d(ACTIVITY_SERVICE, "BA BA BLack SHeep");
			adapter.notifyDataSetChanged();
		}	

	 }

	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sender_connection);
		
		Intent intent = getIntent();
		FilePath = intent.getStringExtra("FilePath");
		FileName = intent.getStringExtra("FileName");
		file = new File(FilePath  + "/"  + FileName);
		currPacketNoDisp = (TextView) findViewById(R.id.currPacketNo);
		currPacketNoDisp.setText("File to be sent: " + FileName);
		Log.d(ACTIVITY_SERVICE,"path of file..." + FilePath + "   " + FileName);
		
		retransmissionQueue =  new Vector<Integer>();
		
		WifiManager wifi = (WifiManager)getSystemService( Context.WIFI_SERVICE );
        if(wifi != null)
        {
            WifiManager.MulticastLock lock = wifi.createMulticastLock("WifiDevices");
            lock.acquire();
            Log.d(ACTIVITY_SERVICE, "lock acquired");
        }
        
        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }
        
        d=wifi.getDhcpInfo();
        
        try {
			WCMulticastAddress = InetAddress.getByName(getServerMultiCastAddress(d.serverAddress));
//			WCMulticastAddress = InetAddress.getByName("224.0.168.1");
			Log.d(ACTIVITY_SERVICE,"trying to connect to..." + d.serverAddress);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		
		try {
			WCSocketSource = new MulticastSocket(WCSourcePort);
		} catch (Exception e) {System.out.println("Exception occured");}
		
		Log.d(ACTIVITY_SERVICE,"Connection Phase initiated.");
		Log.d(ACTIVITY_SERVICE,"trying to connect to..." + WCMulticastAddress.getHostAddress());
		try{
			WCSocketSource.joinGroup(WCMulticastAddress);
			Log.d(ACTIVITY_SERVICE,"Connected to..." + WCMulticastAddress.getHostAddress());
		} catch (IOException e) {
			Log.d(ACTIVITY_SERVICE,"Some error in joining group");
		}
		sinkList = new Vector<SinkStats>();
		
		adapter=new SinkArrayAdapter(this, R.layout.activity_sink_list, sinkList);
		ListView listView = (ListView) findViewById(android.R.id.list);
		listView.setAdapter(adapter);
		
		//sinkList.add(s);
		//adapter.notifyDataSetChanged();
//		setListAdapter(adapter);
		
		new DownloadFilesTask().execute(0);
		
		listenThread = new Thread(new Runnable() {           
            public void run() { 
            	listenConnection();
            }});
        listenThread.start();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.sender_connection, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	public void listenConnection() {
		byte[] buf = new byte[256];
        DatagramPacket connPacket = new DatagramPacket(buf, buf.length);
        try {
        	WCSocketSource.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
        } catch(SocketException e) {}
        
        while(!Thread.currentThread().isInterrupted()) {
	        try {
	       		WCSocketSource.receive(connPacket); 
	       		String received = new String(connPacket.getData(), 0, connPacket.getLength());
	       		MainActivity.WCPacket connP = new MainActivity.WCPacket();
	       		connP.parse(connPacket);

		       	if(connP.type == 'c') {
		       		// Add the sink to the sinkList.
		       		boolean exists = false;
		       		for(int i = 0; i < sinkList.size(); ++i) {
		       			if(sinkList.get(i).addr.getHostAddress().equals(
		       				connPacket.getAddress().getHostAddress())) {
		       				exists = true;
		       			}
		       		}
		       		if(exists == false) {
		       			// Add to sinkList.
		       			//TODO send name in packet
		       			s = new SinkStats("shubham",connPacket.getAddress());
		       			sinkList.add(s);
		       			runOnUiThread(new Runnable() {
		       		     @Override
		       		     public void run() {

		       		    	adapter.notifyDataSetChanged();

		       		     }
		       			});
		       			
		       		}
		        	// send the metadata to the sink at "address" and "port"
		        	// InetAddress sinkAddress = connPacket.getAddress();
		        	// int sinkPort = connPacket.getPort();
	    	    	// sendMetadata(sinkAddress, sinkPort);
	    	    }
	       	} catch(IOException e) { }
    	}
    }
	
	public void sendData(View view){
		listenThread.interrupt();
		synchronized(fileTransferStart ) {
			fileTransferStart = true;
		}
		
		bufferFile();
        sendMetadata();
     
        Log.d(ACTIVITY_SERVICE,"Connection Phase terminated.");
        // Start Feedback Listening Thread.
        
//        feedbackThread = new Thread(new Runnable() {           
//            public void run() { 
//            	listenSNACK();
//            }});
//        feedbackThread.setPriority(Thread.MAX_PRIORITY);
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        runOnUiThread(new Runnable() {
        	@Override
        	public void run() {
        		
        	}
        });   
        //        feedbackThread.start();

        // Start File transfer.
        Log.d(ACTIVITY_SERVICE, "Starting file transfer...");
        startFileTransfer();
        completePendingTransfer();
        //feedbackThread.interrupt();
        Log.d(ACTIVITY_SERVICE, "File Transfer Ended Reliably for all sinks.");
        return;
        
	}
	
	private void bufferFile() {
		/* Read file into memory buffer */
		fileBuffer = new Vector<byte[]> ();
		byte[] readBuf = new byte[DatagramSize - WCHeaderSize]; // Find Data Size.
		FileInputStream inputStream = null;

		try {
			inputStream = new FileInputStream(file);
		} catch (FileNotFoundException e) {
        	System.err.println("Could not open requested file.");
        }

        int readCount; 
        try {
			while((readCount = inputStream.read(readBuf)) != -1) {
				if(readCount != DatagramSize - WCHeaderSize) {
					// Last Packet
					byte[] lastBuf = new byte[readCount];
	               	System.arraycopy(readBuf, 0, lastBuf, 0, readCount);
	               	fileBuffer.addElement(lastBuf);  
	               	break;
				}
				else {
					fileBuffer.addElement(readBuf);
					readBuf = new byte[DatagramSize - WCHeaderSize];  // Assigning new buffer for next packet.
				}
			}
		} catch (IOException e) {
			System.out.println("IOException occured");
		}

		fileBufferSize = fileBuffer.size();
		
    }
	
	
	private void sendMetadata() {
		// Metadata : 
		String metadata = FileName + "&" + Integer.toString(fileBuffer.size());
		byte[] sendBuf = createWCPacket('m', 0, metadata);

		DatagramPacket metadataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSinkPort);   
		for(int i = 0; i < RetryCount; ++i) {
			try {
				WCSocketSource.send(metadataPacket);
			} catch (IOException e) {}
		}
	}
	
//	private void listenSNACK() {
//		//Log.d(ACTIVITY_SERVICE, "Listening to snack");
//		byte[] buf = new byte[1000];
//        DatagramPacket snack = new DatagramPacket(buf, buf.length);
//        try {
//        	WCSocketSource.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
//        } catch(SocketException e) {}
//
//        while(!Thread.currentThread().isInterrupted()) {
//        	//Log.d(ACTIVITY_SERVICE, "Listening to Snack here man");
//	        try {
//	       		WCSocketSource.receive(snack); 
//	       		synchronized(currEOTRound) {
//	        		currEOTRound = 0;
//				}
//	       		//Log.d(ACTIVITY_SERVICE, "Got some Snack here man");
//				
//	        	String received = new String(snack.getData(), 0, snack.getLength());
//	        	MainActivity.WCPacket snackPacket = new MainActivity.WCPacket();
//	        	snackPacket.parse(snack);
//
//	        	if(snackPacket.type == 's') {
//	        		// Add SNACK packet no requested in retransmission Queue.
//	        		String[] nos = new String(snackPacket.dataStr).split("&");
//	        		Log.d(ACTIVITY_SERVICE, snackPacket.dataStr);
//	        		// First no is data is number of lost packets. Update count.
//	        		for(int i = 0; i < sinkList.size(); ++i) {
//		       			if(sinkList.get(i).addr.getHostAddress().equals(
//		       				snack.getAddress().getHostAddress()) && sinkList.get(i).getLostCount() != Integer.parseInt(nos[0])) {
//		       				
//		       				sinkList.get(i).updateCount(Integer.parseInt(nos[0]));
//		       				runOnUiThread(new Runnable() {
//				       		     @Override
//				       		     public void run() {
//				       		    	Log.d(ACTIVITY_SERVICE, "Its here please");
//				       		    	adapter.notifyDataSetChanged();
//
//				       		     }
//				       		});
//		       			}
//		       		}
//
//	        		// Add SNACK packet no requested in retransmission Queue.
//	        		for(int i = 1; i < nos.length; ++i) {
//	        			if(retransmissionQueue.contains(new Integer(nos[i])) == false) {
//	        				retransmissionQueue.add(new Integer(nos[i]));
//	        			}
//	        		}
//	        	}
//	        	else if(snackPacket.type == 'a') {
//	        		String[] fields = new String(snackPacket.dataStr).split("&");
//	        		// Ack recvd from Sink
//	        		for(int i = 0; i < sinkList.size(); ++i) {
//		       			if(sinkList.get(i).addr.getHostAddress().equals(
//		       				snack.getAddress().getHostAddress()) && !sinkList.get(i).isFileTransferComplete()) {
//		       				sinkList.get(i).setFileTransferComplete();
//		       				sinkList.get(i).setFileTransferTime(Integer.parseInt(fields[0]));
//		       			}
//	        		}
//	        		boolean transferflag = true;
//	        		for(int i = 0; i < sinkList.size(); ++i) {
//		       			if( !sinkList.get(i).isFileTransferComplete()) {
//		       				transferflag = false;
//		       			}
//	        		}
//	        		if(transferflag == true) {
//	        			fileTransferComplete = true;
//	        		}
//	        	}
//	       	} catch(IOException e) {}	      
//    	}
//    }
	
	public void startFileTransfer() {
		int currPacketNo = 0;
		int countPackets = fileBuffer.size();

		while(countPackets > 0) {
			currPacketNoDisp.setText("packets sent: " + Integer.toString(currPacketNo) + " & packets remaining: " + Integer.toString(countPackets));
			currPacketNo += startFileTransferRound(currPacketNo, countPackets);
			countPackets = fileBuffer.size() - currPacketNo;
			currEOTNoSource = currPacketNo;
			
//			synchronized (synchObj) {
//				try {
//					synchObj.wait(200);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
			
			try{
				Thread.sleep(100);
			} catch (InterruptedException e) {}
			
			// sendEOTPacket(currEOTNoSource);
//			printFileTransferStatistics();
		}
		currPacketNoDisp.setText("packets sent: " + Integer.toString(currPacketNo) + " & packets remaining: " + Integer.toString(countPackets));

	}
	public int startFileTransferRound(int currPacketNo, int countPackets) {
		Log.d(ACTIVITY_SERVICE, "Starting file transfer round..." + Integer.toString(currPacketNo)+  " "  + Integer.toString(countPackets));
		// Transmit first-time packets.
		DatagramPacket dataPacket;
		int numPackets = transRoundNo;
		interPacketDelay = (long) ((wifiRate - WCRate) * TransWindow / numPackets) ;
		Log.d(ACTIVITY_SERVICE, interPacketDelay + " is packet delay " + numPackets);
		numPackets = Math.min(countPackets, numPackets);
		
		long actTransWindow = 0;
		if(currPacketNo == 0) {
			// Time a round.
			actTransWindow = System.currentTimeMillis();
		}
		
		for(int i = 0; i < numPackets; ++i) {
			byte dataBuf[] = fileBuffer.get(currPacketNo + i);

            byte[] sendBuf = createWCPacket('d', currPacketNo + i, dataBuf);

            //System.out.println(data);

            dataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSinkPort);    
            try {
            	WCSocketSource.send(dataPacket);
            } catch (IOException e) {}
            try{
            	Thread.sleep(interPacketDelay);
            } catch (InterruptedException e) {}
		}
		
		if(currPacketNo == 0) {
			actTransWindow = System.currentTimeMillis() - actTransWindow;
			Log.d(ACTIVITY_SERVICE, "Time is " + actTransWindow);
			EOTRoundTime = actTransWindow;
			// Send timing packet. 
			byte[] timeBuf = createWCPacket('t', 0, Integer.toString((int) actTransWindow).getBytes());
			dataPacket = new DatagramPacket(timeBuf, timeBuf.length, WCMulticastAddress, WCSinkPort);    
	        for(int i = 0; i < 3; ++i) {
	        	try {
	        		WCSocketSource.send(dataPacket);
	        	} 	catch (IOException e) {}
	        	try{
	        		Thread.sleep(interPacketDelay);
	        	} catch (InterruptedException e) {}
	        }

		}
		
		
		
		// Retransmit packets for which SNACK is received.
		int numRetransPackets = snackRoundNo;
		numRetransPackets = Math.min(retransmissionQueue.size(), numRetransPackets);
		
		int packetNo;
		for(int i = 0; i < numRetransPackets; ++i) {
			synchronized(retransmissionQueue){ 
				packetNo = retransmissionQueue.remove(0);  // Remove head of queue
				Log.d(ACTIVITY_SERVICE,"SNACK response sent " + packetNo);
			}
			byte dataBuf[] = fileBuffer.get(packetNo);

            byte[] sendBuf = createWCPacket('d', packetNo, dataBuf);//data.getBytes();			
            

            dataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSinkPort);    
            try {
            	WCSocketSource.send(dataPacket);
            } catch (IOException e) {}
            try{
            	Thread.sleep(interPacketDelay);
            } catch (InterruptedException e) {}
		}
		return numPackets;
	}
	
	
	private void completePendingTransfer() {
		// Completing Pending Transfer to transfer file reliably.
		while(!fileTransferComplete) {
			currEOTNoSource++;
			// sendEOTPacket(currEOTNoSource);  // EOT packet no = last packet no.
			// Send requested packets. 
			int numRequests = retransmissionQueue.size();
			int packetNo;
			DatagramPacket dataPacket;
			for(int i = 0; i < numRequests; ++i) {

				synchronized(retransmissionQueue){ 
					packetNo = retransmissionQueue.remove(0);
				}

				byte dataBuf[] = fileBuffer.get(packetNo);
				
            	byte[] sendBuf = createWCPacket('d', packetNo, dataBuf);

	            dataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSinkPort);    
	            try {
	            	WCSocketSource.send(dataPacket);
	            	Log.d(ACTIVITY_SERVICE, "Sending SNACK Response " + packetNo);
	            } catch (IOException e) {}
	            try{
	            	Thread.sleep(interPacketDelay);
	            } catch (InterruptedException e) {}
			}

			try{
	        	Thread.sleep(EOTRoundTime);
	        } catch (InterruptedException e) {}

	        synchronized(currEOTRound) {
	        	currEOTRound++;
	        }
	    }
	}
	
	public String getServerMultiCastAddress (int i){
		return "224.0." +    
				((i >> 8 ) & 0xFF) + "." +    
		               ((i >> 24 ) & 0xFF ) ;
	}
	
	public byte[] createWCPacket(char t, int n, String d) {
		String packetStr = t + "#" + Integer.toString(n) + "#" + d;
		return packetStr.getBytes();
	}
	
	public byte[] createWCPacket(char t, int n, byte[] d) {
		
		String ph1 = t + "#" + Integer.toString(n) + "#";

		byte[] packet = new byte[ph1.getBytes().length + d.length];
		
		System.arraycopy(ph1.getBytes(), 0, packet, 0, ph1.getBytes().length);
		System.arraycopy(d, 0, packet, ph1.getBytes().length, d.length);
		return packet;
	} 
	
}
