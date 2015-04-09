package com.example.wificastapp;
//Kepler
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Random;
import java.util.Vector;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StrictMode;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class Receiver extends ActionBarActivity {

	public String   s_dns1 ;
	public String   s_dns2;     
	public String   s_gateway;  
	public String   s_ipAddress;    
	public String   s_leaseDuration;    
	public String   s_netmask;  
	public String   s_serverAddress;

	int DatagramSize = 2000;
	int WCHeaderSize = 7;
	InetAddress WCMulticastAddress = null;
	int WCSourcePort = 4567;
	int WCSinkPort = 4576;    
	String Filler = new String("wc"); // To fill EOT and conn packets with some data.
	long SocketTimeout = 1000;
	private int MaxSnackCount = 40;

	// File Variables : 
	private String fileName;
	private Vector<byte[]> fileBuffer;

	private MulticastSocket WCSocketSink = null;
	private FileOutputStream outputStream = null;
	private InetAddress sourceIP = null;
	private long ConnTimeout = 500; // Timeout for CONN packet.
	private long DataTimeout = 2000; // Timeout for Data packet.
	private int fileBufferSize;
	private boolean[] packetsReceived = null;
	private int totalPackets = 0;
	private int currPacketNoSink ; // Denotes the maximum packet no received.
	private int currEOTNo; 	// Stores the current EOT packet no received.Ignores duplicates.
	private Boolean fileTransferComplete = false; // If true, file transfer is complete.
	private Boolean fileTransferStart = false; // if true, file transfer has started.
	private long TransWindowSink = 1000;
	private Thread snackThread = null;
	private Thread listenThread = null;
	private Random rand = new Random(System.currentTimeMillis());
	private boolean[] packetSNACK = null;  // True if packet no snack is sent by someone.
	private int fileBufferNo = 0; // Points to the file buffer element to start writing.

	private ProgressDialog progressDialog;
	DhcpInfo d;
	File dir = null;
	File fileReceived = null;
	private TextView fileN = null;
	private TextView fileP = null;
	private String username = "wificast" ;
	//timers
	long startTime ;
	long fileTransferTime;
	ListenDataTask LDTask;
	SendSnackTask SSTask;
	private boolean windowChanged = false;

	private class SendSnackTask extends AsyncTask<Void, String, Long> {

		@Override
		protected Long doInBackground(Void... arg0) {
			sendSNACK();
			return null;
		}

		private void sendSNACK() {
			while(!Thread.currentThread().isInterrupted()) {
				// File transfer is started. 
				// Pick a random time in a transWindow and send SNACK.
				if(TransWindowSink <= 0) {TransWindowSink = 500;}
				long timeSNACK = rand.nextInt() % TransWindowSink;
				if(timeSNACK < 0) {timeSNACK = timeSNACK * -1;}

				Log.d(ACTIVITY_SERVICE, "Snack sleep time is:" + timeSNACK+" Time:"+(System.currentTimeMillis()-startTime));
				try {
					Thread.sleep(timeSNACK);
				} catch(InterruptedException e) {}

				// For feedback suppression, could listen to SNACK packets sent by others
				// and not requesting for them.


				//find out what are snack packets
				String packetNoSnack = "";
				// Add Header to SNACK packet.

				int countSNACK = 0;
				int lostCount = 0;
				int no = Math.min(currPacketNoSink, fileBuffer.size() - 1);

				if(no > fileBuffer.size() - 50 && !windowChanged) {
					// Near the end of the data transfer phase. Increase snack frequency.
					TransWindowSink = TransWindowSink / 3;
					windowChanged  = true;
				}
				for(int i = no; i >= 0; --i) {
					if(packetsReceived[i] == false) {
						lostCount++;
					}
				}

				for(int i = 0; i <= no; ++i) {
					if(packetsReceived[i] == false && packetSNACK[i] == false) {
						// Packet not recvd and no one has yet SNACKed this.
						packetNoSnack += i + "&";
						countSNACK++;
						if(countSNACK == MaxSnackCount) break;
					}	
				}
				 Log.d(ACTIVITY_SERVICE, "Sending Snack now" + packetNoSnack +   " LC: " + lostCount + "pcno " + currPacketNoSink+" Time:"+(System.currentTimeMillis()-startTime));
				if(lostCount == 0) {
					// There are no missing packets.
					if(currPacketNoSink >= fileBufferSize - 1) {
						// Last packet has been received. Set termination condition to true.
						 Log.d(ACTIVITY_SERVICE, "FT Complete :" + currPacketNoSink+" Time:"+(System.currentTimeMillis()-startTime));
						synchronized(fileTransferComplete) {
							fileTransferComplete = true;
							publishProgress("File Transfer Complete");
						}
						return;
					}
					else {
						currPacketNoSink = currPacketNoSink + 10;  //why??
					}
				}
				//sending snack
				if(lostCount != 0) {
					String progress = "Lost Count is " + lostCount + " Current Packet No is " + currPacketNoSink;
					publishProgress(progress);
					packetNoSnack = lostCount + "&" + packetNoSnack;
					byte[] sendBuf = createWCPacket('s', 0, packetNoSnack); 
					DatagramPacket snackPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
					for(int i = 0; i < 3; ++i) { 
						try {
							WCSocketSink.send(snackPacket);
						} catch (IOException e) { System.out.println("Error in sending SNACK"); }
					}
				}

				// Reset the packetSNACK array.
				for(int i = no; i >= 0; --i) {
					packetSNACK[i] = false;
				}

				// Now sleep for rest of the TransWindow.
				//why this sleep??
				try {
					if(TransWindowSink - timeSNACK > 0) {
						Thread.sleep(TransWindowSink - timeSNACK);
					}
					Log.d(ACTIVITY_SERVICE, "Snack sleep time is:" + (TransWindowSink - timeSNACK)+" Time:"+(System.currentTimeMillis()-startTime));
				} catch (InterruptedException e) {}
			}
		}
		protected void onProgressUpdate(String... progress) {
			 Log.d(ACTIVITY_SERVICE, "BA BA BLack SHeep");
			fileN.setText(progress[0]);
		}
	}

	@SuppressLint("NewApi") 
	private class ListenDataTask extends AsyncTask<Void, String, Long> {

		@Override
		protected Long doInBackground(Void... arg0) {
			connectSource();
			listenFileMetadata();
			// Log.d(ACTIVITY_SERVICE,"File Metadata received.");
			String[] progress = new String[2];
			progress[0] = "Connected to server";
			progress[1] = "File Name: " + fileName + " File is stored at: '/sdCard/Wificast/";
			publishProgress(progress);
			//			runOnUiThread(new Runnable() {
			//				@Override
			//				public void run() {
			//					fileN.setText("Connected to server");
			//					fileP.setText("File Name: " + fileName + " File is stored at: '/sdCard/Wificast/");
			//				}
			//			});

			listenData();      
			writeFile();
			fileTransferTime = (System.currentTimeMillis() - startTime);
			// Log.d(ACTIVITY_SERVICE,"Total time taken : " + Long.valueOf(fileTransferTime));
			// Log.d(ACTIVITY_SERVICE,"File Transfer Completed.");
			progress[0] = "File Transfer Complete. Time taken in milliseconds is "
					+ Long.valueOf(fileTransferTime);
			publishProgress(progress);
			//			runOnUiThread(new Runnable() {
			//				@Override
			//				public void run() {
			//					fileN.setText("File Transfer Complete. Time taken in milliseconds is " + Long.valueOf(fileTransferTime));
			//					fileP.setText("File Name: " + fileName + " File is stored at: '/sdCard/Wificast/");
			//				}
			//			});
			sendAck(fileTransferTime);
			return null;
		}
		private void connectSource() {
			// Log.d(ACTIVITY_SERVICE,"Connecting to source...");
			// Send Conn packet to Source. Sink multicasts the packet.
			byte[] sendBuf = createWCPacket('c', 0, username);
			DatagramPacket connPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
			// Log.d(ACTIVITY_SERVICE,"trying to connect to..." + WCMulticastAddress.getHostAddress());
			for(int i = 0; i < 3; ++i) {
				try {
					WCSocketSink.send(connPacket);
				} catch (IOException e) {
					// Log.d(ACTIVITY_SERVICE,"Error in sending connection packet");
				}
			}
		}

		private void listenFileMetadata() {
			// Log.d(ACTIVITY_SERVICE,"Waiting for metadata");
			byte[] buf = new byte[256];
			DatagramPacket metadataPacket = new DatagramPacket(buf, buf.length);
			try {
				WCSocketSink.setSoTimeout((int) ConnTimeout); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}

			while(true) {
				try {
					WCSocketSink.receive(metadataPacket); 
					// Parse Metadata Packet: 

					// String received = new String(metadataPacket.getData(), 0, metadataPacket.getLength());
					MainActivity.WCPacket metadataP = new MainActivity.WCPacket();

					metadataP.parse(metadataPacket);
					if(metadataP.type == 'm') {
						// Metadata Packet.	
						String[] metadataFields = new String(metadataP.dataStr).split("&");	

						fileName = metadataFields[0];
						fileBufferSize = Integer.parseInt(metadataFields[1]);


						// Create file Buffer.
						fileBuffer = new Vector<byte[]>();
						for(int i = 0; i < fileBufferSize; ++i) {
							fileBuffer.add(null);
						}

						// Set source IP : 
						sourceIP = metadataPacket.getAddress();

						// Create Packet Received Array.
						packetsReceived = new boolean[fileBufferSize];
						// SNACK listening array.
						packetSNACK = new boolean[fileBufferSize];

						try {
							// Log.d(ACTIVITY_SERVICE,"file to be stored: " + fileName + " and size of buffer : " + fileBuffer.size());
							File sdCard = Environment.getExternalStorageDirectory();
							dir = new File (sdCard.getAbsolutePath() + "/Wificast");
							dir.mkdirs();
							fileReceived = new File(dir,fileName);
							outputStream = new FileOutputStream(fileReceived);
						} catch (FileNotFoundException e) {}
						break;  // Connection established. Metadata recvd.
					}	
				} catch(IOException e) {
					// Socket Timed Out. No File Metadata recvd. 
					connectSource(); // try to connect to source again.
				}
			}
		}
		private void listenData() {
			byte[] buf = new byte[DatagramSize];
			DatagramPacket dataPacket = new DatagramPacket(buf, buf.length);

			try {
				WCSocketSink.setSoTimeout((int) DataTimeout); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}

			while(!fileTransferComplete) {
				try {
					WCSocketSink.receive(dataPacket);
					process(dataPacket);
				} 
				catch(IOException e) { }
			}	   
		}

		/*
		 *	Packet Type : currTime # Packet No # Total Packets(in this time window) # Random Characters
		 * 	Packet No. = -1 for EOT packet and Total Packets(till in this time Window)
		 */
		public void process(DatagramPacket p) {
					//Log.d(ACTIVITY_SERVICE,"packet is getting processed"+" Time:"+System.currentTimeMillis());
			String received = new String(p.getData(), 0, p.getLength());

			MainActivity.WCPacket packet = new MainActivity.WCPacket();
			packet.parse(p);

			// EOT Packet Check.
			if(packet.type == 'e'){

				// Check if eot packet is duplicate
				if(currEOTNo != packet.no ) {
					// Not a duplicate EOT packet.
					currEOTNo = packet.no; // Set Curr EOT packet no.
				}
			}
			else if(packet.type == 't') {
				// Timing packet to synchronize time windows. 
				TransWindowSink = Integer.parseInt(new String(packet.data)) + 100;  // May have [TODO]
				// Log.d(ACTIVITY_SERVICE, TransWindowSink + " is the trans window");	
			}
			else if(packet.type == 'd') {
				// To simulate losses
				 Log.d(ACTIVITY_SERVICE,"data packet is received " + packet.no+" Time:"+(System.currentTimeMillis()-startTime));

				if(!fileTransferStart) {
					fileTransferStart = true;
					startTime = System.currentTimeMillis();
					//snackThread.start();
					SSTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
				}

				// Data Packet
				int packetNo = packet.no;
				if(packetsReceived[packetNo] == true) {
					// Dup Packet. Ignore.
				}
				else {
					currPacketNoSink = Math.max(currPacketNoSink, packetNo);
					packetsReceived[packetNo] = true;

					// Add to file buffer.
					fileBuffer.set(packetNo, packet.data);
					writeFile();
				}
			}
			else {
				// Ignore other packets.
			}
		}
		private void sendAck(long fileTransferTime) {
			String packetAck = Integer.toString((int) fileTransferTime);
			byte[] sendBuf = createWCPacket('a', 0, packetAck); 
			DatagramPacket ackPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
			for(int j = 0; j < 3; ++j) {
				for(int i = 0; i < 3; ++i) { 
					try {
						WCSocketSink.send(ackPacket);
					} catch (IOException e) { System.out.println("Error in sending ACK"); }
				}
				try{
					Thread.sleep(200);
				}
				catch(InterruptedException e) {}
			}
			
		}	
		protected void onProgressUpdate(String... progress) {
			 Log.d(ACTIVITY_SERVICE, "BA BA White SHeep");
			fileN.setText(progress[0]);
			fileP.setText(progress[1]);
		}

	}


	@SuppressLint("NewApi") @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_progress_bar);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		//		setContentView(R.layout.activity_receiver);

		Intent intent = getIntent();
		username = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);



		WifiManager wifi = (WifiManager)getSystemService( Context.WIFI_SERVICE );
		if(wifi != null)
		{
			WifiManager.MulticastLock lock = wifi.createMulticastLock("WifiDevices");
			lock.acquire();
			// Log.d(ACTIVITY_SERVICE, "lock acquired");
		}

		if (android.os.Build.VERSION.SDK_INT > 9) {
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
			StrictMode.setThreadPolicy(policy);
		}

		d=wifi.getDhcpInfo();
		//
		//		s_dns1="DNS 1: "+intToIp(d.dns1);
		//		s_dns2="DNS 2: "+intToIp(d.dns2);    
		//		s_gateway="Default Gateway: "+intToIp(d.gateway);    
		//		s_ipAddress="IP Address: "+intToIp(d.ipAddress); 
		//		s_leaseDuration="Lease Time: "+String.valueOf(d.leaseDuration);     
		//		s_netmask="Subnet Mask: "+intToIp(d.netmask);    
		//		s_serverAddress="Server IP: "+intToIp(d.serverAddress);

		try {
			WCMulticastAddress = InetAddress.getByName(getServerMultiCastAddress(d.serverAddress));
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}


		try {
			WCSocketSink = new MulticastSocket(WCSinkPort);
		} catch (Exception e) {System.out.println("Exception occured");}

		try{
			WCSocketSink.joinGroup(WCMulticastAddress);
		} catch (IOException e) {}

		fileN = (TextView) findViewById(R.id.textView1);
		fileP = (TextView) findViewById(R.id.textView2);
		fileN.setText("Trying to connect to source");


		//		setContentView(R.layout.activity_progress_bar);

		//		mProgress = (ProgressBar) findViewById(R.id.progressBar1);

//		snackThread = new Thread(new Runnable() {           
//			public void run() { 
//				sendSNACK();      
//			}});

		LDTask = new ListenDataTask();  
		SSTask = new SendSnackTask();
		LDTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//		listenThread = new Thread(new Runnable() {           
//			public void run() { 
//				connectSource();
//				listenFileMetadata();
//				Log.d(ACTIVITY_SERVICE,"File Metadata received.");
//
//				runOnUiThread(new Runnable() {
//					@Override
//					public void run() {
//						fileN.setText("Connected to server");
//						fileP.setText("File Name: " + fileName + " File is stored at: '/sdCard/Wificast/");
//					}
//				});
//
//				listenData();      
//				writeFile();
//				fileTransferTime = (System.currentTimeMillis() - startTime);
//				Log.d(ACTIVITY_SERVICE,"Total time taken : " + Long.valueOf(fileTransferTime));
//				Log.d(ACTIVITY_SERVICE,"File Transfer Completed.");
//				runOnUiThread(new Runnable() {
//					@Override
//					public void run() {
//						fileN.setText("File Transfer Complete. Time taken in milliseconds is " + Long.valueOf(fileTransferTime));
//						fileP.setText("File Name: " + fileName + " File is stored at: '/sdCard/Wificast/");
//					}
//				});
//				sendAck(fileTransferTime);
//			}});
//
//		listenThread.start();

		//		System.out.println("File Transfer Completed.");

	}


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.receiver, menu);
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

	private void connectSource() {
		// Log.d(ACTIVITY_SERVICE,"Connecting to source...");
		// Send Conn packet to Source. Sink multicasts the packet.
		byte[] sendBuf = createWCPacket('c', 0, username);
		DatagramPacket connPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
		// Log.d(ACTIVITY_SERVICE,"trying to connect to..." + WCMulticastAddress.getHostAddress());
		for(int i = 0; i < 3; ++i) {
			try {
				WCSocketSink.send(connPacket);
			} catch (IOException e) {
				// Log.d(ACTIVITY_SERVICE,"Error in sending connection packet");
			}
		}
	}

	private void listenFileMetadata() {
		// Log.d(ACTIVITY_SERVICE,"Waiting for metadata");
		byte[] buf = new byte[256];
		DatagramPacket metadataPacket = new DatagramPacket(buf, buf.length);
		try {
			WCSocketSink.setSoTimeout((int) ConnTimeout); // Timeout Socket to un-block at receive()
		} catch(SocketException e) {}

		while(true) {
			try {
				WCSocketSink.receive(metadataPacket); 
				// Parse Metadata Packet: 

				// String received = new String(metadataPacket.getData(), 0, metadataPacket.getLength());
				MainActivity.WCPacket metadataP = new MainActivity.WCPacket();

				metadataP.parse(metadataPacket);
				if(metadataP.type == 'm') {
					// Metadata Packet.	
					String[] metadataFields = new String(metadataP.dataStr).split("&");	

					fileName = metadataFields[0];
					fileBufferSize = Integer.parseInt(metadataFields[1]);


					// Create file Buffer.
					fileBuffer = new Vector<byte[]>();
					for(int i = 0; i < fileBufferSize; ++i) {
						fileBuffer.add(null);
					}

					// Set source IP : 
					sourceIP = metadataPacket.getAddress();

					// Create Packet Received Array.
					packetsReceived = new boolean[fileBufferSize];
					// SNACK listening array.
					packetSNACK = new boolean[fileBufferSize];

					try {
						// Log.d(ACTIVITY_SERVICE,"file to be stored: " + fileName + " and size of buffer : " + fileBuffer.size());
						File sdCard = Environment.getExternalStorageDirectory();
						dir = new File (sdCard.getAbsolutePath() + "/Wificast");
						dir.mkdirs();
						fileReceived = new File(dir,fileName);
						outputStream = new FileOutputStream(fileReceived);
					} catch (FileNotFoundException e) {}
					break;  // Connection established. Metadata recvd.
				}	
			} catch(IOException e) {
				// Socket Timed Out. No File Metadata recvd. 
				connectSource(); // try to connect to source again.
			}
		}
	}





	private void listenData() {
		byte[] buf = new byte[DatagramSize];
		DatagramPacket dataPacket = new DatagramPacket(buf, buf.length);

		try {
			WCSocketSink.setSoTimeout((int) DataTimeout); // Timeout Socket to un-block at receive()
		} catch(SocketException e) {}

		while(!fileTransferComplete) {
			try {
				WCSocketSink.receive(dataPacket);
				process(dataPacket);
			} 
			catch(IOException e) { }
		}	   
	}

	/*
	 *	Packet Type : currTime # Packet No # Total Packets(in this time window) # Random Characters
	 * 	Packet No. = -1 for EOT packet and Total Packets(till in this time Window)
	 */
	public void process(DatagramPacket p) {
				//Log.d(ACTIVITY_SERVICE,"packet is getting processed dp"+" Time:"+System.currentTimeMillis());
		String received = new String(p.getData(), 0, p.getLength());

		MainActivity.WCPacket packet = new MainActivity.WCPacket();
		packet.parse(p);

		// EOT Packet Check.
		if(packet.type == 'e'){

			// Check if eot packet is duplicate
			if(currEOTNo != packet.no ) {
				// Not a duplicate EOT packet.
				currEOTNo = packet.no; // Set Curr EOT packet no.
			}
		}
		else if(packet.type == 't') {
			// Timing packet to synchronize time windows. 
			TransWindowSink = Integer.parseInt(new String(packet.data)) + 100;  // May have [TODO]
			// Log.d(ACTIVITY_SERVICE, TransWindowSink + " is the trans window");	
		}
		else if(packet.type == 'd') {
			// To simulate losses
			// Log.d(ACTIVITY_SERVICE,"data packet is received " + packet.no);

			if(!fileTransferStart) {
				fileTransferStart = true;
				startTime = System.currentTimeMillis();
				//snackThread.start();
			}

			// Data Packet
			int packetNo = packet.no;
			if(packetsReceived[packetNo] == true) {
				// Dup Packet. Ignore.
			}
			else {
				currPacketNoSink = Math.max(currPacketNoSink, packetNo);
				packetsReceived[packetNo] = true;

				// Add to file buffer.
				fileBuffer.set(packetNo, packet.data);
				writeFile();
			}
		}
		else {
			// Ignore other packets.
		}
	}

	/*
	 * sending NACK of 10 packets.
	 */
//	private void sendSNACK() {
//		while(!Thread.currentThread().isInterrupted()) {
//			// File transfer is started. 
//			// Pick a random time in a transWindow and send SNACK.
//			if(TransWindowSink <= 0) {TransWindowSink = 200;}
//			long timeSNACK = rand.nextInt() % TransWindowSink;
//			if(timeSNACK < 0) {timeSNACK = timeSNACK * -1;}
//
//			// Log.d(ACTIVITY_SERVICE, "Snack sleep time is:" + timeSNACK);
//			try {
//				Thread.sleep(timeSNACK);
//			} catch(InterruptedException e) {}
//
//			// For feedback suppression, could listen to SNACK packets sent by others
//			// and not requesting for them.
//
//
//			//find out what are snack packets
//			String packetNoSnack = "";
//			// Add Header to SNACK packet.
//
//			int countSNACK = 0;
//			int lostCount = 0;
//			int no = Math.min(currPacketNoSink, fileBuffer.size() - 1);
//			
//
//			for(int i = no; i >= 0; --i) {
//				if(packetsReceived[i] == false) {
//					lostCount++;
//				}
//			}
//
//			for(int i = 0; i <= no; ++i) {
//				if(packetsReceived[i] == false && packetSNACK[i] == false) {
//					// Packet not recvd and no one has yet SNACKed this.
//					packetNoSnack += i + "&";
//					countSNACK++;
//					if(countSNACK == MaxSnackCount) break;
//				}	
//			}
//			// Log.d(ACTIVITY_SERVICE, "Sending Snack now" + packetNoSnack +   " LC: " + lostCount + "pcno" + currPacketNoSink);
//			if(lostCount == 0) {
//				// There are no missing packets.
//				if(currPacketNoSink >= fileBufferSize - 1) {
//					// Last packet has been received. Set termination condition to true.
//					// Log.d(ACTIVITY_SERVICE, "FT Complete :" + currPacketNoSink);
//					synchronized(fileTransferComplete) {
//						fileTransferComplete = true;
//					}
//					return;
//				}
//				else {
//					currPacketNoSink = currPacketNoSink + 10;
//				}
//			}
//			if(lostCount != 0) {
//				packetNoSnack = lostCount + "&" + packetNoSnack;
//				byte[] sendBuf = createWCPacket('s', 0, packetNoSnack); 
//				DatagramPacket snackPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
//				for(int i = 0; i < 3; ++i) { 
//					try {
//						WCSocketSink.send(snackPacket);
//					} catch (IOException e) { System.out.println("Error in sending SNACK"); }
//				}
//			}
//
//			// Reset the packetSNACK array.
//			for(int i = no; i >= 0; --i) {
//				packetSNACK[i] = false;
//			}
//
//			// Now sleep for rest of the TransWindow.
//			try {
//				if(TransWindowSink - timeSNACK > 0) {
//					Thread.sleep(TransWindowSink - timeSNACK);
//				}
//				// Log.d(ACTIVITY_SERVICE, "Snack sleep time is:" + (TransWindowSink - timeSNACK));
//			} catch (InterruptedException e) {}
//		}
//	}

//	private void sendAck(long fileTransferTime) {
//		String packetAck = Integer.toString((int) fileTransferTime);
//		byte[] sendBuf = createWCPacket('a', 0, packetAck); 
//		DatagramPacket ackPacket = new DatagramPacket(sendBuf, sendBuf.length, sourceIP, WCSourcePort);  
//		for(int i = 0; i < 3; ++i) { 
//			try {
//				WCSocketSink.send(ackPacket);
//			} catch (IOException e) { System.out.println("Error in sending ACK"); }
//		}
//	}

	//	private void sendSNACK() {
	//		Log.d(ACTIVITY_SERVICE,"code is in sendSNACK");
	//		while(!Thread.currentThread().isInterrupted()) {
	//			// File transfer is started. 
	//			// Pick a random time in a transWindow and send SNACK.
	//			long timeSNACK = rand.nextInt() % TransWindowSink;
	//			if(timeSNACK < 0) {timeSNACK = timeSNACK * -1;}
	//
	//			try {
	//				Thread.sleep(timeSNACK);
	//			} catch(InterruptedException e) {}
	//
	//			// For feedback suppression, could listen to SNACK packets sent by others
	//			// and not requesting for them.
	//
	//
	//			//find out what are snack packets
	//			String packetNoSnack = "";
	//			// Add Header to SNACK packet.
	//
	//			int countSNACK = 0;
	//			int no = Math.min(currPacketNoSink, fileBuffer.size() - 1);
	//
	//			for(int i = no; i >= 0; --i) {
	//				if(packetsReceived[i] == false) {
	//					packetNoSnack += i + "&";
	//					countSNACK++;
	//					if(countSNACK == 10) break;
	//				}	
	//			}
	//			if(countSNACK == 0) {
	//				// There are no missing packets.
	//				if(currPacketNoSink == fileBufferSize - 1) {
	//					// Last packet has been received. Set termination condition to true.
	//					synchronized(fileTransferComplete) {
	//						fileTransferComplete = true;
	//					}
	//					return;
	//				}		
	//			}
	//
	//			if(countSNACK > 0) {
	//				Log.d(ACTIVITY_SERVICE,"number of packets to be snacked: " + packetNoSnack);
	//				byte[] sendBuf = createWCPacket('s', 0, packetNoSnack); 
	//				DatagramPacket snackPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
	//				try {
	//					WCSocketSink.send(snackPacket);
	//				} catch (IOException e) { System.out.println("Error in sending SNACK"); }
	//			}
	//
	//			// Now sleep for rest of the TransWindow.
	//			try {
	//				Thread.sleep(TransWindowSink - timeSNACK);
	//			} catch (InterruptedException e) {}
	//		}
	//	}

	private void writeFile() {
		for(int i = fileBufferNo; i < fileBuffer.size(); ++i) {
			if(fileBuffer.get(i) != null) {
				try { 
					outputStream.write(fileBuffer.get(i));
					fileBuffer.set(i, null);
				} catch (IOException e) {
					// Log.d(ACTIVITY_SERVICE,"Error in writing to file."); 
					return;
				}	
			} 
			else {
				// Hole in the filebuffer. Break;
				fileBufferNo = i;
				break;
			}
		}
	}
	public byte[] createWCPacket(char t, int n, String d) {
		String packetStr = t + "#" + Integer.toString(n) + "#" + d;
		return packetStr.getBytes();
	}

	public byte[] createWCPacket(char t, int n, byte[] d) {

		String ph1 = t + "#" + Integer.toString(n) + "#";

		byte[] packet = new byte[ph1.getBytes().length + d.length];

		//arraycopy(Object src, int srcPos, Object dest, int destPos, int length)
		System.arraycopy(ph1.getBytes(), 0, packet, 0, ph1.getBytes().length);
		System.arraycopy(d, 0, packet, ph1.getBytes().length, d.length);
		return packet;
	} 

	public String intToIp(int i) {
		return ( i & 0xFF) + "." +
				((i >> 8 ) & 0xFF) + "." +    
				((i >> 16 ) & 0xFF) + "." +
				((i >> 24 ) & 0xFF ) ;
	}

	public String getServerMultiCastAddress (int i){
		return "224.0." +    
				((i >> 8 ) & 0xFF) + "." +    
				((i >> 24 ) & 0xFF ) ;
	}

}
