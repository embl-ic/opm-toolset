package opm.smoke;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Java preferences kept in memory, for a Fiji started by scripts/smoke-online-deskew.ps1.
 *
 * <p>On Windows, Java preferences live in the registry of the user, shared by every Java program
 * of that user - another Fiji included. A test Fiji that quits for real writes there. Selected with
 * {@code -Djava.util.prefs.PreferencesFactory=opm.smoke.IsolatedPreferencesFactory}, this keeps
 * them in the test JVM instead: the run starts with none and leaves nothing behind. (ImageJ's own
 * preferences file is isolated separately, by pointing {@code user.home} at a folder of the run.)
 *
 * <p>Not part of the plugin: the smoke script compiles it, puts it on the test Fiji's class path
 * for the run, and removes it afterwards.
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {

	private static final Preferences USER = new Node(null, "");
	private static final Preferences SYSTEM = new Node(null, "");

	@Override
	public Preferences userRoot() {
		return USER;
	}

	@Override
	public Preferences systemRoot() {
		return SYSTEM;
	}

	private static final class Node extends AbstractPreferences {
		private final Map<String, String> values = new HashMap<String, String>();
		private final Map<String, Node> children = new HashMap<String, Node>();

		Node(Node parent, String name) {
			super(parent, name);
		}

		@Override protected void putSpi(String key, String value) {
			values.put(key, value);
		}

		@Override protected String getSpi(String key) {
			return values.get(key);
		}

		@Override protected void removeSpi(String key) {
			values.remove(key);
		}

		@Override protected void removeNodeSpi() {
			children.clear();
			values.clear();
		}

		@Override protected String[] keysSpi() {
			return values.keySet().toArray(new String[0]);
		}

		@Override protected String[] childrenNamesSpi() {
			return children.keySet().toArray(new String[0]);
		}

		@Override protected AbstractPreferences childSpi(String name) {
			Node child = children.get(name);
			if (child == null) children.put(name, child = new Node(this, name));
			return child;
		}

		@Override protected void syncSpi() {
			// nothing is stored outside this JVM
		}

		@Override protected void flushSpi() {
			// nothing is stored outside this JVM
		}
	}
}
