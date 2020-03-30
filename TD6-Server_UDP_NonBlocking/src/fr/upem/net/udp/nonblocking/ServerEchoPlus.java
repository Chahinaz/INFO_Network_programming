package fr.upem.net.udp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.logging.Logger;

/**
 * Classe repr�sentant un serveur avec le protocole EchoPlus monitor� par une seule
 * DataGramChannel par le selecteur, ont est d'abord en OP_READ ou on attend d'�tre 
 * notifi� quand un paquet arrive, on effectue le read, on v�rifie qu'il a fonctionner
 * et on passe au write.
 * @author jonat
 *
 */
public class ServerEchoPlus {
	private static final Logger logger = Logger.getLogger(ServerEcho.class.getName());

	private final DatagramChannel dc;
	private final Selector selector;
	private final int BUFFER_SIZE = 1024;
	private final ByteBuffer buffRec = ByteBuffer.allocateDirect(BUFFER_SIZE); // pour les r�ception
	private final ByteBuffer buffSend = ByteBuffer.allocateDirect(BUFFER_SIZE); // pour les envoi
	private SocketAddress exp; // expediteur , connaitre la client qui nous a contact�
	private int port;

	public ServerEchoPlus(int port) throws IOException {
		this.port = port;
		selector = Selector.open();
		dc = DatagramChannel.open();
		dc.bind(new InetSocketAddress(port));
		dc.configureBlocking(false);
		dc.register(selector, SelectionKey.OP_READ); // On attend la r�ception de paquets

	}

	/**
	 * Bloque jusqu'a l'arriv�e d'un paquet D�s qu'un paquet arrive le selecteur va
	 * rempli l'ensemble Selected Keys avec la cl� de notre selecteur -> Appel de la
	 * m�thode tratKey
	 * 
	 * @throws IOException
	 */
	public void serve() throws IOException {
		logger.info("ServerEchoPlus started on port " + port);
		try {
			while (!Thread.interrupted()) {
				selector.select(this::treatKey);
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

	}

	private void treatKey(SelectionKey key) {
		try {
			if (key.isValid() && key.isWritable()) {
				doWrite(key);
			}
			if (key.isValid() && key.isReadable()) {
				doRead(key);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

	}

	private void doRead(SelectionKey key) throws IOException {
		buffRec.clear(); // toujours av le receive
		exp = dc.receive(buffRec);
		if (exp == null) { // on v�rifie que l'expediteur est pas null
			logger.info("The selector gave a wrong hint."); // Le selecteur s'est tromp�
			return;
		}
		buffRec.flip(); // on flip() le buffer de r�ception et on rempli le buffer d'envoi ensuite
		buffSend.clear();
		while(buffRec.hasRemaining()) { // on parcours le buffer de r�ception jusqu'a avoir �puis� la zone de travail
			buffSend.put((byte) (buffRec.get() + 1 %255)); // On put dans le bufferSend des donn�es incr�ment�es 1 modulo 255
		}
		buffSend.flip();
		key.interestOps(SelectionKey.OP_WRITE);
		
		/*
		var i = buff.position();
		while (i < buff.limit()) {
			var incementedData = ((buff.get(i) + 1) % 255);
			buff.put((byte) incementedData); 
			i++;
		}
		buff.flip();
		*/
	}
	
	/**
	 * Traiter le paquet re�u
	 * @param key
	 * @throws IOException
	 */
	private void doWrite(SelectionKey key) throws IOException {
		dc.send(buffSend, exp); // Envoyer les donn�es qui sont dans le bufferSend � l'expediteur concern�
		if (buffSend.hasRemaining()) { // si le send a bien fonctionner
			logger.info("The selector gave a wrong hint."); // Le selecteur s'est tromp�
			return;
		}
		key.interestOps(SelectionKey.OP_READ);
	}

	public static void usage() {
		System.out.println("Usage : ServerEchoPlus port");
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			usage();
			return;
		}
		ServerEchoPlus server = new ServerEchoPlus(Integer.valueOf(args[0]));
		server.serve();
	}
}
