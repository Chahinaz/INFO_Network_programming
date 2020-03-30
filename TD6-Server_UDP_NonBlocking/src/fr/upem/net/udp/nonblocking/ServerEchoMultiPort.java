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
 * Classe qui poss�de plusieurs datagramChannel qui vont �tre "bind�s" sur une
 * plage de ports, impliquant une suveillance de plusieurs datagramChannel par le
 * Selecteur.
 * 
 * @author jonat
 *
 */
public class ServerEchoMultiPort {
	private static final Logger logger = Logger.getLogger(ServerEcho.class.getName());

	private final Selector selector;
	private final int BUFFER_SIZE = 1024;
	private int portDebutPlage;
	private int portFinPlage;

	public ServerEchoMultiPort(int portDebutPlage, int portFinPlage) throws IOException {

		this.portDebutPlage = checkIfPortIsInvalid(portDebutPlage);
		this.portFinPlage = checkIfPortIsInvalid(portFinPlage);
		selector = Selector.open(); // Cr�ation du selecteur

		for (var i = portDebutPlage; i <= portFinPlage; i++) { // Attention port de fin de plage inclus! Autant de dc que de ports
			var dc = DatagramChannel.open();
			dc.bind(new InetSocketAddress(i)); 
			dc.configureBlocking(false);
			dc.register(selector, SelectionKey.OP_READ, new Context()); // On attend la r�ception de paquets
		}
	}

	/**
	 * precondition
	 * 
	 * @param port
	 * @return port with correct value
	 */
	private static int checkIfPortIsInvalid(Integer port) {
		if (port == null || port <= 0) {
			throw new IllegalArgumentException();
		}
		return port;
	}

	/**
	 * Classe interne pour stocker pour chaque SelectionKey un ByteBuffer et une
	 * InetSocketAdress
	 * Pour chaque key on va attacher un objet Context
	 * @author jonat
	 *
	 */
	private class Context {
		private final ByteBuffer buff = ByteBuffer.allocateDirect(BUFFER_SIZE);
		private SocketAddress exp; // InetSocketAdress extends SocketAdress
	}

	/**
	 * Bloque jusqu'a l'arriv�e d'un paquet D�s qu'un paquet arrive le selecteur va
	 * rempli l'ensemble Selected Keys avec la cl� de notre selecteur -> Appel de la
	 * m�thode treatKey
	 * 
	 * @throws IOException
	 */
	public void serve() throws IOException {
		logger.info("ServerEchoMultiPort started with plage [" + portDebutPlage + " " + portFinPlage + "]");
		try {
			while (!Thread.interrupted()) {
				selector.select(this::treatKey); // consumer
			}
		} catch (UncheckedIOException tunneled) {
			throw tunneled.getCause();
		}

	}

	private void treatKey(SelectionKey key) {
		try {
			if (key.isValid() && key.isWritable()) { // Est-ce qu'on peut faire un write sur cette cl� ?
				doWrite(key);
			}
			if (key.isValid() && key.isReadable()) { // Est-ce que la cl� peut recevoir ?
				doRead(key);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

	}

	private void doRead(SelectionKey key) throws IOException {
		var dc = (DatagramChannel) key.channel(); // selectionKey.channel = getter pour le lire le dc associ� � la cl�
		var context = (Context) key.attachment(); // Attachement : Retourne l'objet couramment attach� � la cl�
		var buff = context.buff; // recup�re le buffer avec son contexte dans une variable
		
		buff.clear(); // toujours av le receive
		context.exp = (InetSocketAddress) dc.receive(buff);
		buff.flip(); // flip � la r�ception
		
		if(context.exp != null) {
			key.interestOps(SelectionKey.OP_WRITE); // getter pour l'envoi
		} else {
			logger.warning("The selector gave a wrong hint (OP_READ)."); //Le selecteur s'est tromp�
		}
		/*
		var context = (Context) key.attachment(); 
		context.buff.clear(); 
		context.exp = ((DatagramChannel) key.channel()).receive(context.buff); 

		if (context.exp == null) {
			logger.warning("The selector gave a wrong hint (OP_READ).");
			return;
		}
		context.buff.flip(); 
		key.interestOps(SelectionKey.OP_WRITE);
		*/
	}

	private void doWrite(SelectionKey key) throws IOException {
		var dc = (DatagramChannel) key.channel(); // selectionKey.channel = getter pour le lire le dc associ� � la cl�
		var context = (Context) key.attachment(); // Attachement : Retourne l'objet couramment attach� � la cl�
		var buff = context.buff; // recup�re le buffer avec son contexte dans une variable
		
		dc.send(buff, context.exp);
		
		if(!buff.hasRemaining()) {
			key.interestOps(SelectionKey.OP_READ);
		} else {
			logger.warning("The selector gave a wrong hint (OP_WRITE)."); //Le selecteur s'est tromp�
		}
		/*
		var context = (Context) key.attachment(); 
		((DatagramChannel) key.channel()).send(context.buff, context.exp);
		if (context.buff.hasRemaining()) {
			return;
		}
		*/
	}

	public static void usage() {
		System.out.println("Usage : ServerEchoMultiPort port_debut_plage  port_fin_plage");
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 2 || Integer.valueOf(args[0]) > Integer.valueOf(args[1])) {
			usage();
			return;
		}
		ServerEchoMultiPort server = new ServerEchoMultiPort(Integer.valueOf(args[0]), Integer.valueOf(args[1]));
		server.serve();
	}
}
