package station;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import http.IpUtils;
import http.RmiClassServer;
import provider.Provider;
import provider.ProviderUtils;
import sensor.SensorServer;
import sensor.SensorState;

public class StationImpl extends UnicastRemoteObject implements Station {
	private static final long serialVersionUID = 1615162418507733656L;

	public static void main(String[] args) {
		if (args == null || args.length != 1) {
			System.out.println("Usage: StationImpl xmlFile");
			System.exit(-1);
		}
		try {
			new StationImpl(new File(args[0]));
		} catch (RemoteException e) {
			System.err.println(e.getMessage());
		}
	}

	private String stationName;
	private Provider provider;
	private Document doc;

	private LinkedHashMap<String, SensorServer> sensors;

	public StationImpl() throws RemoteException {
		super();
	}

	public StationImpl(File xml) throws RemoteException {
		sensors = new LinkedHashMap<>();

		doc = parseXml(xml);
		String providerUrl = findProvider();
		initRmi(providerUrl);
		loadSensors();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				provider.unregisterStation(stationName);
			} catch (Exception e1) {
				System.err.println(e1.getMessage());
			}
			sensors.forEach((n, s) -> {
				try {
					s.tearDown();
					provider.unregister(stationName, n);
				} catch (Exception e) {
					System.err.println("Error unregistering " + n);
					e.printStackTrace();
				}
			});
		}));
	}

	private void loadSensors() {
		NodeList nl = doc.getElementsByTagName("sensor");
		for (int i = 0; i < nl.getLength(); i++) {
			Element e = (Element) nl.item(i);
			String klass = e.getAttribute("class");
			String name = e.getElementsByTagName("name").item(0).getTextContent();
			File propertyFile = new File(e.getElementsByTagName("parameters").item(0).getTextContent());
			boolean loadNow = e.getElementsByTagName("loadAtStartup").item(0).getTextContent().equals("true");

			if (sensors.containsKey(name)) {
				System.err.println("A sensor named " + name + " already exists");
			} else {
				try {
					SensorServer ss = (SensorServer) getClass().getClassLoader().loadClass(klass).newInstance();
					ss.loadParametersFromFile(propertyFile);
					sensors.put(name, ss);
					if (loadNow)
						startSensor(name);
				} catch (RemoteException | InstantiationException | IllegalAccessException
						| ClassNotFoundException ex) {
					System.err.println(ex.getMessage());
				}
			}
		}
	}

	private String findProvider() {
		try {
			return ProviderUtils.findProviderUrl();
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}

		stationName = doc.getDocumentElement().getAttribute("name");
		if (doc.getElementsByTagName("provider").getLength() != 0) {
			String providerIp = ((Element) doc.getElementsByTagName("provider").item(0)).getAttribute("ip");
			int providerPort = Integer
					.parseInt(((Element) doc.getElementsByTagName("provider").item(0)).getAttribute("port"));
			return ProviderUtils.buildProviderUrl(providerIp, providerPort);
		} else {
			System.err.println("No provider host discovered in multicast nor found in xml");
			System.exit(-4);
		}
		return null;
	}

	private Document parseXml(File xml) {
		// see:
		// http://stackoverflow.com/questions/15732/whats-the-best-way-to-validate-an-xml-file-against-an-xsd-file
		Source xmlFile = new StreamSource(xml);
		SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		Schema schema;
		try {
			schema = schemaFactory.newSchema(new File("stationSchema.xsd"));
			schema.newValidator().validate(xmlFile);
			System.out.println(xmlFile.getSystemId() + " is valid");
		} catch (SAXException | IOException e) {
			System.err.println(xmlFile.getSystemId() + " is NOT valid");
			System.err.println("Reason: " + e.getLocalizedMessage());
			System.exit(-5);
		}

		try {
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(xml);

			// optional, but recommended
			// read this -
			// http://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
			doc.getDocumentElement().normalize();
			return doc;
		} catch (IOException | SAXException | ParserConfigurationException e) {
			System.err.println("Error parsing xml file: " + xml);
			System.exit(-6);
		}
		return null;
	}

	private void initRmi(String providerUrl) {
		// Impostazione del SecurityManager
		if (!new File("rmi.policy").canRead()) {
			System.out.println(
					"Unable to load security policy, assure that you have rmi.policy in the directory you launched ProviderRMI in");
			System.exit(-3);
		}
		System.setProperty("java.security.policy", "rmi.policy");
		if (System.getSecurityManager() == null) {
			System.setSecurityManager(new SecurityManager());
		}

		String currentHostname;
		try {
			currentHostname = IpUtils.getCurrentIp().getHostAddress();
			System.out.println("currentHostName: " + currentHostname);
			// Avvia un server http affinchè altri possano scaricare gli stub di
			// questa classe
			// da questo codebase in maniera dinamica quando serve
			// (https://publicobject.com/2008/03/java-rmi-without-webserver.html)
			RmiClassServer rmiClassServer = new RmiClassServer();
			rmiClassServer.start();
			System.setProperty("java.rmi.server.hostname", currentHostname);
			System.setProperty("java.rmi.server.codebase",
					"http://" + currentHostname + ":" + rmiClassServer.getHttpPort() + "/");
		} catch (SocketException | UnknownHostException e) {
			System.err.println("Unable to retrieve current ip: " + e.getMessage());
			System.exit(-7);
		}

		try {
			// Ricerca del providerHost e registrazione
			System.out.println("Looking up provider on: " + providerUrl);
			provider = (Provider) Naming.lookup(providerUrl);
			System.out.println("Connessione al ProviderRMI completata");
		} catch (MalformedURLException | RemoteException | NotBoundException e) {
			System.err.println("Error finding provider: " + e.getMessage());
		}

		try {
			stationName = doc.getDocumentElement().getAttribute("name");
			provider.registerStation(stationName, this);
			System.out.println("Registrazione di " + stationName + " al provider completata");
		} catch (RemoteException e) {
			System.err.println("Unable to register station on provider: " + e.getMessage());
		}
	}

	@Override
	public synchronized List<String> listSensors(SensorState state) throws RemoteException {
		if (state == null)
			return new LinkedList<>(sensors.keySet());
		List<String> result = new LinkedList<>();
		sensors.forEach((name, sensor) -> {
			try {
				if (sensor.getState() == state)
					result.add(name);
			} catch (Exception ignore) {
				System.err.println(ignore.getMessage());
			}
		});
		return result;
	}

	@Override
	public synchronized SensorState getSensorState(String name) throws RemoteException {
		if (name == null || name.isEmpty() || !sensors.containsKey(name))
			throw new RemoteException("Name not found");
		return sensors.get(name).getState();
	}

	@Override
	public synchronized void startSensor(String name) throws RemoteException {
		if (name == null || name.isEmpty() || !sensors.containsKey(name))
			throw new RemoteException("Name not found");
		SensorServer s = sensors.get(name);
		switch (s.getState()) {
		case FAULT:
			throw new RemoteException("Sensor fault, unable to enable");
		case SHUTDOWN:
			try {
				s.setUp();
			} catch (Exception e) {
				s.tearDown();
				throw new RemoteException(e.getMessage(), e);
			}
			provider.register(stationName, name, s);
			break;
		default:
			break;
		}
	}

	@Override
	public synchronized void stopSensor(String name) throws RemoteException {
		if (name == null || name.isEmpty() || !sensors.containsKey(name))
			throw new RemoteException("Name not found");

		sensors.get(name).tearDown();
		provider.unregister(stationName, name);
	}
}