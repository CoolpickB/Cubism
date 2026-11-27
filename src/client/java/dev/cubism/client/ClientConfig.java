package dev.cubism.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The client options in {@code config/cubism-client.properties}. The file is written with the defaults on first start. */
public final class ClientConfig {
	private static final String SHOW_STORAGE_LINE = "showStorageLine";
	private static final String EDGE_CAMERA_TILT = "edgeCameraTilt";
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cubism-client.properties");
	private static final Properties PROPERTIES = read();

	/** Whether the look, the walking yaw and the face of the player go to logs/cubism-look.log while the view frame is tilted. */
	public static final boolean traceLook = Boolean.parseBoolean(PROPERTIES.getProperty("traceLook", "false"));

	/** Whether /cubelight and /cubevoxy are registered. They are only of use when debugging the mod. */
	public static final boolean debugCommands = FabricLoader.getInstance().isDevelopmentEnvironment()
		|| Boolean.parseBoolean(PROPERTIES.getProperty("debugCommands", "false"));

	/** Whether each world load writes a timeline to logs/cubism-load-<time>.log, see LoadTrace. */
	public static final boolean traceLoads = Boolean.parseBoolean(PROPERTIES.getProperty("traceLoads", "false"));

	/**
	 * Debug, set with {@code /cubevoxy hide}: where Voxy hides its levels of detail. 0 nowhere, 1 behind the sections
	 * that Sodium draws (Voxy alone), 2 also behind the chunks that Sodium will build.
	 */
	public static int voxyHide = 2;

	/** Debug, set with {@code /cubevoxy only}: the one face that Voxy draws, or -1 for all faces. */
	public static int voxyOnly = -1;

	/** Whether F3 shows the line with the storage position, which is only of use when debugging the mod. */
	public static final boolean showStorageLine = Boolean.parseBoolean(PROPERTIES.getProperty(SHOW_STORAGE_LINE, "false"));

	/**
	 * Whether the camera on the ground turns with the ground over a rounded edge. Off, the camera stays upright on the
	 * face and turns at the seam, for players who feel sick from the tilt. Set in the Accessibility options.
	 */
	private static boolean edgeCameraTilt = Boolean.parseBoolean(PROPERTIES.getProperty(EDGE_CAMERA_TILT, "true"));

	private ClientConfig() {
	}

	public static boolean edgeCameraTilt() {
		return edgeCameraTilt;
	}

	public static void setEdgeCameraTilt(boolean value) {
		edgeCameraTilt = value;
		PROPERTIES.setProperty(EDGE_CAMERA_TILT, Boolean.toString(value));
		store(PROPERTIES);
	}

	private static Properties read() {
		Properties properties = new Properties();
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				properties.load(reader);
			} catch (IOException e) {
				return properties;
			}
		}
		if (properties.getProperty(SHOW_STORAGE_LINE) == null) {
			properties.setProperty(SHOW_STORAGE_LINE, "false");
			store(properties);
		}
		return properties;
	}

	private static void store(Properties properties) {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			properties.store(writer, "Cubism client options. showStorageLine: show the storage position on the F3 screen. edgeCameraTilt: true or false.");
		} catch (IOException ignored) {
			// The values in memory still apply when the file cannot be written.
		}
	}
}
