package sensorstation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.reflections.Reflections;

import http.IpUtils;
import http.RmiClassServer;
import implementations.SensorServer;
import provider.Provider;
import sensor.SensorParameter;

public class SensorStationCli {
	private String stationName;
	private String providerHost;
	private int providerPort;
	private BufferedReader console;
	private Provider provider;

	public SensorStationCli(String[] args) {

		try {
			loadStationParameters("station.properties");
		} catch (IOException e) {
			System.out.println("Parameters loading from station.properties failed");
			e.printStackTrace();
		}

		console = new BufferedReader(new InputStreamReader(System.in));

		try {
			askForParameters();
		} catch (IOException e1) {
			System.out.println("IOException while asking for parameters");
			e1.printStackTrace();
			System.exit(1);
		}

		try {
			initRmi();
		} catch (SocketException | UnknownHostException | MalformedURLException | RemoteException | NotBoundException e) {
			System.out.println("Impossible to register sensor");
			e.printStackTrace();
			System.exit(2);
		}

		while (true)
			mainMenu();
	}

	private void mainMenu() {
		System.out.println("Operazioni disponibili:");
		System.out.println("1.\tAvvia sensore");
		System.out.println("2.\tStato sensore");
		System.out.println("3.\tFerma sensore");
		System.out.print("> ");
		int choice = 0;
		try {
			choice = Integer.parseInt(console.readLine());
		} catch (IOException | NumberFormatException e) {
			e.printStackTrace();
			return;
		}
		switch (choice) {
		case 1:
			List<Class<? extends SensorServer>> list = getServersList();
			if (list.isEmpty()) {
				System.out.println("No loadable sensor classes found");
				break;
			}
			for (int j = 0; j < list.size(); j++) {
				System.out.println(j + ".\t" + list.get(j).getSimpleName());
			}

			System.out.print("> ");
			try {
				choice = Integer.parseInt(console.readLine());
			} catch (IOException | NumberFormatException e) {
				e.printStackTrace();
				return;
			}

			SensorServer s = initSensor(list.get(choice));
			if (s == null) {
				System.out.println("Errore di inizializzazione");
				return;
			}

			System.out.print("Nome del sensore? ");
			String sensorName = null;
			try {
				sensorName = console.readLine().trim();
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}

			try {
				provider.register(stationName, sensorName, s);
			} catch (RemoteException e) {
				System.out.println("Errore di registrazione");
				e.printStackTrace();
				return;
			}
			break;
		case 2:

			break;
		case 3:

			break;
		default:
			System.out.println("Scelta non riconosciuta: " + choice);
			break;
		}
	}

	private void initRmi() throws SocketException, UnknownHostException, MalformedURLException, RemoteException, NotBoundException {
		// Impostazione del SecurityManager
		if (System.getSecurityManager() == null) {
			System.setSecurityManager(new SecurityManager());
		}

		String currentHostname = IpUtils.getCurrentIp().getHostAddress();
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

		// Ricerca del providerHost e registrazione
		String completeName = "rmi://" + providerHost + ":" + providerPort + "/" + "ProviderRMI";
		provider = (Provider) Naming.lookup(completeName);
		System.out.println("Connessione al ProviderRMI completata");
	}

	private void askForParameters() throws IOException {
		while (stationName == null || providerHost.isEmpty()) {
			System.out.print("Station name? ");
			stationName = console.readLine().trim();
		}
		while (providerHost == null || providerHost.isEmpty()) {
			System.out.print("Provider address? ");
			providerHost = console.readLine().trim();
		}
		while (providerPort < 1 || providerPort > 65535) {
			System.out.print("Provider port? ");
			providerPort = Integer.parseInt(console.readLine().trim());
		}
	}

	public final void loadStationParameters(String propertyFile) throws IOException {
		loadStationParameters(propertyFile, false);
	}

	public void loadStationParameters(String propertyFile, boolean isXml) throws IOException {
		if (propertyFile == null)
			throw new IllegalArgumentException();
		Properties properties = new Properties();
		InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(propertyFile);
		if (isXml)
			properties.loadFromXML(inputStream);
		else
			properties.load(inputStream);
		inputStream.close();

		stationName = properties.getProperty("stationName", "");
		System.out.println("stationName: " + stationName);
		providerHost = properties.getProperty("providerIp", "");
		System.out.println("providerHost: " + providerHost);
		providerPort = Integer.parseInt(properties.getProperty("providerPort", "0"));
		System.out.println("providerPort: " + providerPort);
	}

	/**
	 * Performs all the necessary operations to init a sensor: loading its
	 * parameters, asking the user for missing parameters, calling setUp() on
	 * the sensor
	 * 
	 * @param sensorClass
	 * @param properyFile
	 * @return a functioning sensor or null if something went wrong
	 */
	private SensorServer initSensor(Class<? extends SensorServer> sensorClass) {
		assert sensorClass != null;

		SensorServer s;
		try {
			s = sensorClass.getConstructor().newInstance();
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e1) {
			e1.printStackTrace();
			System.out.println("Impossibile instanziare un sensore");
			return null;
		}

		System.out.print("File da cui caricare i parametri? ");
		try {
			String fileName = console.readLine().trim();
			if (!fileName.trim().isEmpty())
				s.loadParametersFromFile(new File(fileName));
		} catch (IOException e) {
			// errore di lettura da console
			e.printStackTrace();
			return null;
		}

		for (Field f : s.getAllSensorParameterFields())
			try {
				if (f.isAnnotationPresent(SensorParameter.class))
					if (f.get(s) != null)
						System.out.println(f.getAnnotation(SensorParameter.class).userDescription() + ":\t" + f.get(s));
					else {
						Class<?> typeToParse = f.getType();
						if (!SensorParameter.validTypes.contains(typeToParse)) {
							System.out.println("Sensor contains a parameter field that is not of types "
									+ SensorParameter.validTypes);
							return null;
						}

						String value;
						boolean retry = true;
						do {
							System.out.print(f.getAnnotation(SensorParameter.class).userDescription() + " ("
									+ typeToParse.getSimpleName() + ")? ");
							value = console.readLine().trim();
							if (!value.isEmpty())
								try {
									typeToParse.getMethod("valueOf", String.class).invoke(null, value.trim());
									retry = false;
								} catch (InvocationTargetException ignore) {
									// probabilemte un problema di parsing dei
									// numeri
									System.out.println("Exception: " + ignore.getTargetException().getMessage());
									retry = true;
								} catch (NoSuchMethodException e) {
									// non dovrebbe mai avvenire perchè tutti i
									// campi di SensorParameter.validTypes
									// hanno il metodo valueOf(String)
									return null;
								}
						} while (retry);
					}
			} catch (IllegalAccessException | IOException e) {
				e.printStackTrace();
				return null;
			}

		try {
			s.setUp();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		return s;
	}

	/**
	 * Scans the classes through reflection to find all the subclasses of
	 * {@link SensorServer}
	 * 
	 * @return a list of subclasses of {@link SensorServer}
	 */
	public static List<Class<? extends SensorServer>> getServersList() {
		// https://code.google.com/archive/p/reflections/
		Reflections reflections = new Reflections("");
		Set<Class<? extends SensorServer>> subTypes = reflections.getSubTypesOf(SensorServer.class);
		return new ArrayList<>(subTypes);
	}

	public static void main(String[] args) {
		new SensorStationCli(args);
	}

}
