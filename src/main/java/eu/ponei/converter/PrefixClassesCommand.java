package eu.ponei.converter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

final class PrefixClassesCommand implements Command {
	@Override
	public String name() {
		return "prefix-classes";
	}

	@Override
	public String usage() {
		return "converter prefix-classes <prefix> <input.tiny|input.mapping> <output.tiny|output.mapping>";
	}

	@Override
	public int run(List<String> args, PrintStream out, PrintStream err) throws Exception {
		if (args.size() != 3) {
			err.println("Usage: " + usage());
			return 2;
		}

		Path input = Path.of(args.get(1));
		Path output = Path.of(args.get(2));
		new MappingClassPrefixer().prefix(input, output, args.getFirst());
		out.println("Wrote " + output);
		return 0;
	}
}
