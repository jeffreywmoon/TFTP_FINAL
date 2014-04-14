/**
 * TftpMain.java
 * Contains main, checks input, and maintains the tftpclient object
 * @author Theron Rabe, Jeffrey moon
 * @date 4/1/14
 * 
 * The front-end to the tftpclient
 */
package main;
import java.io.IOException;

public class TftpMain {

	public static void main(String[] args) throws IOException {
	
		if(args.length == 4 && (args[0].equals("netascii") || args[0].equals("octet")) &&
				(args[1].equals("get") || args[1].equals("put"))) {		//make sure usage is safe
			
			int myTID = 1025 + (int)(Math.random() * ((65535 - 1025) + 1));			//grab a safe random port
			String strServerAddr = args[2];							//grab server address
			String strMode = args[0];							//select a mode
			
			TftpClient tftpclient = new TftpClient(strServerAddr, myTID, strMode);		//Establish a client

			if(args[1].equals("put")) {
				System.out.println(tftpclient.send(args[3]) + " bytes written.");	//write file and display
				System.out.println("PUT successful");

			} else if(args[1].equals("get")) {
				System.out.println(tftpclient.receive(args[3]) + " bytes retrieved.");	//read file and display
				System.out.println("GET successful");
			}
			tftpclient.close();								//close established client
		} else {
			System.out.println("Usage: java tftp [netascii|octet] [get|put] [serverAddress] [filename]\n");	//display help message
		}
	}
}
