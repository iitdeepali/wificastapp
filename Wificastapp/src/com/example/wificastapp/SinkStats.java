package com.example.wificastapp;

import java.net.InetAddress;

public class SinkStats {
	public String username;
	public InetAddress addr;	
	public int lostCount;
	public int fileTransferTime;
	public boolean fileTransferComplete = false;

	public SinkStats() {}

	public SinkStats(String name, InetAddress a) {
		username = name;
		addr = a;
		lostCount = 0;
		fileTransferTime = 0;
	}

	public void updateCount(int l) {
		lostCount = l;
	}
	
	 public String getName()
	 {
		 return username;
	 }
	 
	 public InetAddress getIP()
	 {
		 return addr;
	 }
	 
	 public int getLostCount()
	 {
		 return lostCount;
	 }
	 public void setFileTransferTime(int t) 
	 {
		 fileTransferTime = t;
	 }
	 public boolean isFileTransferComplete()
	 {
		 return fileTransferComplete;
	 }
	 public void setFileTransferComplete() 
	 {
		 fileTransferComplete = true;
	 }
}
