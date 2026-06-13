package eu.ponei.converter;

final class ObfuscationHeuristic {
	private static final int MIN_DEOBFUSCATED_CLASS_NAME_LENGTH = 4;
	private static final int MIN_DEOBFUSCATED_MEMBER_NAME_LENGTH = 5;

	private ObfuscationHeuristic() {
	}

	static boolean classNameLooksDeobfuscated(String name) {
		String simpleName = substringAfterLast(name, '/');
		String innermostName = substringAfterLast(simpleName, '$');
		return innermostName.length() >= MIN_DEOBFUSCATED_CLASS_NAME_LENGTH;
	}

	static boolean memberNameLooksDeobfuscated(String name) {
		return !name.startsWith("<") && name.length() >= MIN_DEOBFUSCATED_MEMBER_NAME_LENGTH;
	}

	static boolean fieldNameLooksDeobfuscated(String name) {
		return name.startsWith("_") || memberNameLooksDeobfuscated(name);
	}

	private static String substringAfterLast(String value, char separator) {
		int index = value.lastIndexOf(separator);
		return index >= 0 ? value.substring(index + 1) : value;
	}
}
