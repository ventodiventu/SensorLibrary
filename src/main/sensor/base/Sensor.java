package sensor.base;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The basic interface that all sensor servers must provide. Extends
 * {@link Remote} so that a remote reference to the sensor can be sent using
 * RMI. Defines only a method to get the state of a sensor.
 */
public interface Sensor extends Remote {
	/**
	 * Gives information about the state of a sensor, states are defined in
	 * {@link SensorState}
	 * 
	 * @return the sensor state
	 * @throws RemoteException
	 */
	public SensorState getState() throws RemoteException;
}