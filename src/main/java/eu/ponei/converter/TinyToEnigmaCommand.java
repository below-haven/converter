package eu.ponei.converter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

final class TinyToEnigmaCommand implements Command {
	private static final String ADD_PACKAGE_PREFIX = "--add-package-prefix";

	@Override
	public String name() {
		return "tiny-to-enigma";
	}

	@Override
	public String usage() {
		return "converter tiny-to-enigma [--add-package-prefix] <input.tiny> <output.enigma>";
	}

	@Override
	public int run(List<String> args, PrintStream out, PrintStream err) throws Exception {
		boolean addPackagePrefix = false;
		if (!args.isEmpty() && args.getFirst().equals(ADD_PACKAGE_PREFIX)) {
			addPackagePrefix = true;
			args = args.subList(1, args.size());
		}

		if (args.size() != 2) {
			err.println("Usage: " + usage());
			return 2;
		}

		Path input = Path.of(args.get(0));
		Path output = Path.of(args.get(1));
		new TinyToEnigmaConverter().convert(input, output, addPackagePrefix);
		out.println("Wrote " + output);
		return 0;
	}
}
