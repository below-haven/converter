package eu.ponei.converter;

import java.nio.file.Files;
import java.nio.file.Path;

final class TinyToEnigmaConverter {
	private final MappingTreeModelReader reader = new MappingTreeModelReader();
	private final MappingModelMerger merger = new MappingModelMerger();
	private final EnigmaModelWriter writer = new EnigmaModelWriter();

	void convert(Path input, Path output) throws ConversionException {
		convert(input, output, false);
	}

	void convert(Path input, Path output, boolean addPackagePrefix) throws ConversionException {
		if (!Files.isRegularFile(input)) {
			throw new ConversionException("Input Tiny file does not exist: " + input);
		}

		MappingModel current = reader.readTiny(input, addPackagePrefix);

		if (Files.exists(output)) {
			merger.mergeInto(current, reader.readEnigma(output));
		}

		writer.writeAtomically(current, output);
	}
}
