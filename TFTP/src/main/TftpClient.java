package main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.Arrays;

/**
 * @author 	Jeffrey Moon, Theron Rabe
 * @date	4/1/2014
 *
 * contains all required methods needed for an octet/netascii send/receive. This is specific to UNIX machines,
 * as it replaces all instances of CRLF with LF after a successful GET
 */
public class TftpClient {
	
	private int serverTID = 0;
	private byte[] mode;
	
	private String modeString;
	
	private DatagramSocket udpSocket;
	private InetAddress serverAddr;
	
	// CONSTANTS
	private final byte terminator = 0;
	
	// All 2-byte opcodes, actual opcode is at index 1
	private final byte[] READOP = {0,1};
	private final byte[] WRITEOP = {0,2};
	private final byte[] DATAOP = {0,3};
	private final byte[] ACKOP = {0, 4};
	private final byte[] ERROROP = {0, 5};
	
	public void testbati(byte[] b){
		System.out.println(byteArrayToInt(b));
	}
	/**
	 * Constructor
	 * 
	 * @param strServerAddr Textual representation of either server hostname or IP address
	 * @param myTID			The TID (also port) that was randomly selected for this connection
	 * @param mode			The transfer mode; either 'octet', 'netascii', or 'mail'
	 */
	public TftpClient(String strServerAddr, int myTID, String mode){
		try {
			serverAddr = InetAddress.getByName(strServerAddr);	//Lookup address
		} catch (UnknownHostException e) {
			ErrorHandler.error(8);						//error, if not found
		}													//Set TID
		this.mode = mode.getBytes();						//Set mode
		this.modeString = mode;
		try {
			udpSocket = new DatagramSocket(myTID);  //Make socket
			udpSocket.setSoTimeout(10000);			//Set socket timeout value
		} catch (SocketException e) {
			ErrorHandler.error(11);
		}				
						
	}

	/**
	 * Gracefully frees client resources
	 */
	public void close() {
		udpSocket.close();
	}
	
	/**
	 * Sends a file to the tftp server using mode 'octet'
	 * @param strFilename The file that will be sent to the tftp server
	 * @throws IOException 
	 * @return Number of bytes written
	 */
	@SuppressWarnings("resource")
	public long send(String strFilename) throws IOException {
		FileInputStream in = null;		
		File filedir = new File(strFilename);		//Grab the file
		
		ByteArrayOutputStream bytestream = new ByteArrayOutputStream();
	
		byte[] fileBytes = new byte[512];			//allocate read buffer
		byte[] file;
		
		int blockNum = 0;							//initialize state
		int bytesRead = 0;
		int offset = 0;
		long totalBytes = 0;
		
		DatagramPacket initPacket = createInitPacket(WRITEOP, strFilename);	//build WRQ
		DatagramPacket dataPacket;
		
		
		sendPacket(initPacket);						//Send WRQ
		
		grabPacket(initPacket, initPacket);			//Wait for reply
		
		if(checkAck(initPacket, blockNum)){			//if reply is ACK...
			blockNum++;
			serverTID = initPacket.getPort();		//grab server TID

			try {
				in = new FileInputStream(filedir);
			} catch (FileNotFoundException e1) {
				ErrorHandler.error(1);
			}	//ready to read file
			try {
				while((bytesRead = in.read(fileBytes)) != -1) bytestream.write(fileBytes, 0, bytesRead);
			} catch (IOException e) {
				ErrorHandler.error(1);
			}
			
			String fileString = bytestream.toString();
			if(modeString.equals("netascii"))
				fileString = fileString.replaceAll("\n", "\r\n"); // add CR if mode is netascii
			
			file = fileString.getBytes();
			int bytesLeft = file.length;
			
			
			// MAIN TRANSMISSION LOOP
			do {									//repeatedly...
				if(bytesLeft >= 512) {
					System.arraycopy(file, offset, fileBytes, 0, 512);	 //read a chunk
					offset+=512;
					bytesLeft-=512;
					bytesRead = 512;
				} else {
					System.arraycopy(file, offset, fileBytes, 0, bytesLeft);
					bytesRead = bytesLeft;
				}
					
				dataPacket = createDataPacket(blockNum, fileBytes, bytesRead);	//turn into packet

				sendPacket(dataPacket);					//send packet
				
				int tries = 10;						
				do {
					if(tries==0) ErrorHandler.error(10); //break if received incorrect TID 10 times
					grabPacket(dataPacket, dataPacket);			//wait for proper reply
					--tries;
				} while (dataPacket.getPort() != serverTID);

				if(checkAck(dataPacket, blockNum)) {		//if reply is ACK
					blockNum++;									//change values, continue
					totalBytes += bytesRead;			
				} else {
					ErrorHandler.error(extractError(dataPacket));	//else, error
					break;
				}
			} while(bytesRead==512);						//until packet is terminal
		} else {
			ErrorHandler.error(extractError(initPacket));
		}
		return totalBytes;									//return total bytes transferred
	}


	/**
	 * @param strFilename	File to pull from tftp server
	 * @throws IOException	
	 * @return Number of bytes read
	 */
	public long receive(String strFilename) throws IOException{
		long totalBytes = 0;

		ByteArrayOutputStream bytestream = new ByteArrayOutputStream();				//prepare to write
		FileOutputStream fos = new FileOutputStream(new File("./" + strFilename));	//to file
		
		byte[] dataPacketArray = new byte[516];										//initialize packet
		DatagramPacket dataPacket = new DatagramPacket(dataPacketArray, dataPacketArray.length, serverAddr, 0);
		DatagramPacket lastPacket;
		int blockNum = 1;
		
		DatagramPacket initPacket = createInitPacket(READOP, strFilename);			//create RRQ
		sendPacket(initPacket);														//send RRQ
		lastPacket = initPacket;
		do{																			//repeatedly...
			grabPacket(dataPacket, lastPacket);										//wait for packet
			lastPacket = dataPacket;
			if(serverTID == 0) {
				serverTID = dataPacket.getPort();									//set serverTID, if not set
			} else {
				while(dataPacket.getPort() != serverTID) {
					grabPacket(dataPacket, lastPacket);								//ensure correct server
				}
				lastPacket = dataPacket;
			}
			
			if(checkDataPacket(dataPacket, blockNum)){								//if no errors
				sendPacket(createAckPacket(blockNum));								//send ACK
				lastPacket = createAckPacket(blockNum);
				blockNum++;															//advance state
				bytestream.write(Arrays.copyOfRange(dataPacket.getData(), 4, dataPacket.getLength()));
				totalBytes += dataPacket.getLength() - 4;					//write to memory
			}else{
				ErrorHandler.error(extractError(dataPacket));			//else print error
			}
		} while(dataPacket.getLength() - 4 == 512);	//until packet is terminal
		
		byte[] outFileBytes;
		if(modeString.equals("netascii")){	
			String fileString = bytestream.toString();
			fileString = fileString.replaceAll("\r\n", "\n");
			outFileBytes = fileString.getBytes();
		}else{
			outFileBytes = bytestream.toByteArray();
		}
	
	
	
		fos.write(outFileBytes);						//write bytes to file
		System.out.println(outFileBytes.length + " bytes written to file " + strFilename);
		fos.close();								//close file
		return totalBytes;							//return file size
	}
	
	/**
	 * @param opcode		The opcode (either WRQ or RRQ) for the initialization packet
	 * @param strFileName	The file name to send/receive from server
	 * @return				The init packet
	 */
	private DatagramPacket createInitPacket(byte[] opcode, String strFileName){
		byte[] filename = strFileName.getBytes();
		
		// Length of the data portion of the init packet in bytes. The +2 accounts for the 2 terminator bytes
		int newArrayLen = WRITEOP.length + filename.length + mode.length + 2;
		
		// Byte array used for storing data portion of init packet
		byte[] initPacketArray = concatByteArray(new byte[][] {opcode, filename, {terminator}, mode, {terminator}}, newArrayLen);
		DatagramPacket initPacket = new DatagramPacket(initPacketArray, initPacketArray.length, serverAddr, 69);
		initPacket.setData(initPacketArray);
		return initPacket;
	}
	
	/**
	 * Creates data packet for transmission, returns the packet 
	 * 
	 * @param blockNum	The current block number which will be used for the data packet
	 * @param fileBytes	The bytes read from file, length of this array is 512 (not all bytes have to be used)
	 * @param bytesRead	The actual number of bytes read from the file
	 * @return 			The data packet
	 */
	private DatagramPacket createDataPacket(int blockNum, byte[] fileBytes, int bytesRead){
		byte[] outByteArray = new byte[bytesRead + 4];	
		byte[] blockNumBytes = intToByteArray(blockNum);
		
		System.arraycopy(DATAOP, 0, outByteArray, 0, DATAOP.length);
		System.arraycopy(blockNumBytes, 0, outByteArray, 2, blockNumBytes.length);
		System.arraycopy(fileBytes, 0, outByteArray, 4, bytesRead);
		DatagramPacket dataPacket = new DatagramPacket(outByteArray, outByteArray.length, serverAddr, serverTID);
		dataPacket.setData(outByteArray);
		return dataPacket;
	}
	
	/**
	 * @param blockNum	The block number of the data packet we wish to ack
	 * @return			The full ack packet, ready for transmission
	 */
	private DatagramPacket createAckPacket(int blockNum){
		byte[] ackArray = {0, 4, 0, 0};
		System.arraycopy(intToByteArray(blockNum), 0, ackArray, 2, 2);
		DatagramPacket ack = new DatagramPacket(ackArray, ackArray.length, serverAddr, serverTID);
		return ack;
	}

	/**
	 * @param in	2D byte array that will be concatenated in big endian into a 1D byte
	 * @param len	Length of new 1D byte aray
	 * @return		1D concatenated byte array
	 */
	private byte[] concatByteArray(byte[][] b, int len){
		byte[] out = new byte[len];
		int offset = 0;
		for(int i = 0; i < b.length; i++){
			System.arraycopy(b[i], 0, out, offset, b[i].length);
			offset += b[i].length;
		}
		return out;	
	}
	private void sendPacket(DatagramPacket pack){
		try{
			udpSocket.send(pack);
		} catch (Exception e) {
			ErrorHandler.error(9);
		}
	}
	/**
	 * Grabs next packet from socket, timing out if needed.
	 * @param sock	UDP socket we are using
	 * @param pack	data packet
	 */
	private void grabPacket(DatagramPacket pack, DatagramPacket lastPack) {
		
		try {									// to receive packet
			udpSocket.receive(pack);
		} catch (SocketTimeoutException e) {	// if socket timeouts..
			int tries = 5;
			while(tries>0){						// Try to receive packet several more times
				try {
					System.out.println("Error: Timeout. Resending last packet...");
					udpSocket.send(lastPack);	// Send last packet
					udpSocket.receive(pack);	// Attempt to receive packet
				} catch (SocketTimeoutException e1) { // If another timeout...
					--tries;					
				} catch (Exception e1){
					ErrorHandler.error(0);
				}
			}
			if(tries==0) ErrorHandler.error(9);
		} catch (Exception e) {
			ErrorHandler.error(9);
		}
	}
	
	/**
	 * Checks to see if a packet is ACK, returns true if ACK
	 * 
	 * @param ack		potential ACK packet from server
	 * @param blockNum	Block number of datagram the ACK refers to
	 * @return 			is this an acknowledgement?
	 */
	private boolean checkAck(DatagramPacket ack, int blockNum){
		byte[] byBlockNum = new byte[2];
		System.arraycopy(ack.getData(), 2, byBlockNum, 0, 2);
		if(ack.getData()[1] == ACKOP[1] && byteArrayToInt(byBlockNum) == blockNum) 
			return true;
		else return false;
	}

	/**
	 * Extracts an error number from an error packet
	 */
	private int extractError(DatagramPacket p) {
		if(p.getData()[1] == ERROROP[1]) {
			return (int) p.getData()[3];
		} else {
			return 0;
		}
	}
	
	/**
	 * Checks data packet from server to see if it is correctly formed
	 * 
	 * @param dataPacket	The data packet that was received from the server
	 * @param blockNum		The previously-acked block number to compare against new block number
	 * @return				True if data packet is correctly formulated
	 */
	private boolean checkDataPacket(DatagramPacket dataPacket, int blockNum){
		byte[] opcode = new byte[2];
		byte[] blockNumByte = new byte[2];
		opcode = Arrays.copyOf(dataPacket.getData(), 2);			// extract opcode
		blockNumByte = Arrays.copyOfRange(dataPacket.getData(), 2, 4); // extract lock num
		if ((opcode[1] == DATAOP[1]) && (blockNum == byteArrayToInt(blockNumByte))){ //if correctly formed data packet
			return true;
		}
		else return false;
		
	}
	/**
	 * @param b	The byte array that will be converted into an int representation
	 * @return		int that has been converted from a 2-byte byte array
	 */
	private static int byteArrayToInt(byte[] b){
		return   (int)(b[1] & 0xFF)| 
	    		(int)((b[0] & 0xFF) << 8);
	    		
	
		
		
	}
	
	/**
	 * @param a the int to be converted into a 2-byte byte array
	 * @return 	the 2-byte byte array from the int
	 */
	public static byte[] intToByteArray(int a){
	    return new byte[] {   
	        (byte) (a >> 8),   
	        (byte) (a)
	    };
	}
}
