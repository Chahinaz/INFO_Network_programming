package fr.upem.net.udp.nonblocking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;


public class ClientIdUpperCaseUDPBurst {

	private static Logger logger = Logger.getLogger(ClientIdUpperCaseUDPBurst.class.getName());
	private static final Charset UTF8 = Charset.forName("UTF8");
	private static final int BUFFER_SIZE = 1024;

	private enum State {SENDING, RECEIVING, FINISHED};

	private final List<String> lines;
	private final String[] upperCaseLines;
	private final int timeout;
	private final InetSocketAddress serverAddress;
	private final DatagramChannel dc;
	private final Selector selector;
	private final SelectionKey uniqueKey;
	
	private final ByteBuffer senderBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
	private final ByteBuffer receivedBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
	private long lastSend;
	private final BitSet receivedSet; // BitSet marking received requests
	private int numberOfLinesReceived;
	private final int nbLines;

	private State state;

	private static void usage() {
		System.out.println("Usage : ClientIdUpperCaseUDPBurst in-filename out-filename timeout host port ");
	}

	public ClientIdUpperCaseUDPBurst(List<String> lines, int timeout, InetSocketAddress serverAddress) throws IOException {
		nbLines = lines.size();
		this.lines = lines;
		upperCaseLines = new String[nbLines];
		this.timeout = timeout;
		this.serverAddress = serverAddress;
		this.dc = DatagramChannel.open();
		dc.configureBlocking(false);
		dc.bind(null);
		this.selector = Selector.open();
		this.uniqueKey = dc.register(selector, SelectionKey.OP_WRITE);
		this.receivedSet = new BitSet(nbLines);
		this.state = State.SENDING;
	}


	public static void main(String[] args) throws IOException, InterruptedException {
		if (args.length != 5) {
			usage();
			return;
		}

		String inFilename = args[0];
		String outFilename = args[1];
		int timeout = Integer.valueOf(args[2]);
		String host = args[3];
		int port = Integer.valueOf(args[4]);
		InetSocketAddress serverAddress = new InetSocketAddress(host, port);

		//Read all lines of inFilename opened in UTF-8
		List<String> lines = Files.readAllLines(Paths.get(inFilename), UTF8);
		//Create client with the parameters and launch it
		ClientIdUpperCaseUDPBurst client = new ClientIdUpperCaseUDPBurst(lines, timeout, serverAddress);
		var upperCaseLines = Arrays.asList(client.launch());
		Files.write(Paths.get(outFilename), upperCaseLines, UTF8,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING);

	}


	private String[] launch() throws IOException, InterruptedException {
		Set<SelectionKey> selectedKeys = selector.selectedKeys();
		while (!isFinished()) {
			selector.select(updateInterestOps());
			for (SelectionKey key : selectedKeys) {
				if (key.isValid() && key.isWritable()) {
					doWrite();
				}
				if (key.isValid() && key.isReadable()) {
					doRead();
				}
			}

			selectedKeys.clear();
		}
		dc.close();
		return upperCaseLines;
	}

	/**
	 * Updates the interestOps on key based on state of the context
	 *
	 * @return the timeout for the next select (0 means no timeout)
	 */

	private int updateInterestOps() {
		var time = System.currentTimeMillis();
		// Si l'etat vaut SENDING ou que le temps est sup�rieur � l'heure du dernier envoi + le timeout alors
		if (this.state == State.SENDING || time > this.lastSend + this.timeout) { 
			this.uniqueKey.interestOps(SelectionKey.OP_WRITE); // On passe en OP_WRITE
			return 0;
		}
		if (state == State.RECEIVING) { // R�ception
			uniqueKey.interestOps(SelectionKey.OP_READ);
		}
		var delay = (int) (this.lastSend + this.timeout - time); // d�lai � retourner
		return delay;
	}

	private boolean isFinished() {
		return state == State.FINISHED;
	}

	/**
	 * Performs the receptions of packets
	 *
	 * @throws IOException
	 */
	private void doRead() throws IOException {
		var time = System.currentTimeMillis();
		
		while (System.currentTimeMillis() < time + timeout) {
			this.receivedBuffer.clear(); // toujours av le receive
			var exp = this.dc.receive(this.receivedBuffer);
		
			if (exp == null) {
				logger.info("expeditor is null");
				continue;
			}
			this.receivedBuffer.flip();
			var idLine = (int) receivedBuffer.getLong();

			if (!this.receivedSet.get(idLine)) {
				var decodedData = UTF8.decode(this.receivedBuffer).toString();
				this.upperCaseLines[idLine] = decodedData;
				this.receivedSet.set(idLine);
				this.numberOfLinesReceived++;
			}			
		}
		
		// Statuer sur l'etat du client
		if (this.nbLines == this.numberOfLinesReceived) {
			this.state = State.FINISHED;
		} else {
			this.state = State.SENDING;
		}
			
	}

	/**
	 * Tries to send the packets
	 *
	 * @throws IOException
	 */
	private void doWrite() throws IOException {
		
		var idLine = 0;
		while(idLine < this.nbLines) {
			if (!this.receivedSet.get(idLine)) {
				this.senderBuffer.clear(); // On nettoie le buffer d'envoi
				this.senderBuffer.putLong(idLine); // on met dans le buffer l'ID  courant
				var encodedData = UTF8.encode(lines.get(idLine)); //on encode les lignes en fonction de la ligne r�cup�r�e (ID)
				this.senderBuffer.put(encodedData); // On met les donn�es encod�es dans le buffer d'envoi
				
				this.dc.send(this.senderBuffer.flip(), this.serverAddress);
				this.lastSend = System.currentTimeMillis();
			}
			idLine++;
		}
		this.state = State.RECEIVING;
	}
}