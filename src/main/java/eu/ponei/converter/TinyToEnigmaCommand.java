package eu.ponei.converter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

final class TinyToEnigmaCommand implements Command {
	@Override
	public String name() {
		return "tiny-to-enigma";
	}

	@Override
	public String usage() {
		return "converter tiny-to-enigma <input.tiny> <output.enigma>";
	}

	@Override
	public int run(List<String> args, PrintStream out, PrintStream err) throws Exception {
		if (args.size() != 2) {
			err.println("Usage: " + usage());
			return 2;
		}

		Path input = Path.of(args.get(0));
		Path output = Path.of(args.get(1));
		new TinyToEnigmaConverter().convert(input, output);
		out.println("Wrote " + output);
		return 0;
	}
}
