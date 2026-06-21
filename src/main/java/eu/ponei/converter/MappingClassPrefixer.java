package eu.ponei.converter;

import static eu.ponei.converter.MappingNamespaces.INTERMEDIARY;
import static net.fabricmc.mappingio.tree.MappingTreeView.NULL_NAMESPACE_ID;
import static net.fabricmc.mappingio.tree.MappingTreeView.SRC_NAMESPACE_ID;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class MappingClassPrefixer {
	void prefix(Path input, Path output, String rawPrefix) throws ConversionException {
		if (!Files.isRegularFile(input)) {
			throw new ConversionException("Input mapping file does not exist: " + input);
		}

		if (samePath(input, output)) {
			throw new ConversionException("Input and output paths must be different");
		}

		String prefix = normalizePrefix(rawPrefix);
		String inputName = input.getFileName().toString();
		String outputName = output.getFileName().toString();
		if (inputName.endsWith(".tiny")) {
			requireOutputExtension(outputName, ".tiny", output);
			prefixTiny(input, output, prefix);
		} else if (inputName.endsWith(".mapping")) {
			requireOutputExtension(outputName, ".mapping", output);
			prefixEnigma(input, output, prefix);
		} else {
			throw new ConversionException("Unsupported mapping file extension: " + input);
		}
	}

	private void prefixTiny(Path input, Path output, String prefix) throws ConversionException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try {
			MappingReader.read(input, tree);
		} catch (IOException e) {
			throw new ConversionException("Failed to read Tiny file: " + input, e);
		}

		int intermediaryNs = tree.getNamespaceId(INTERMEDIARY);
		if (intermediaryNs == NULL_NAMESPACE_ID) {
			throw new ConversionException("Tiny file is missing namespace: " + INTERMEDIARY);
		}

		if (intermediaryNs == SRC_NAMESPACE_ID) {
			throw new ConversionException("Cannot prefix Tiny files where intermediary is the source namespace");
		}

		for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
			String className = classMapping.getName(intermediaryNs);
			classMapping.setDstName(prefixedName(prefix, className), intermediaryNs);
		}

		writeTinyAtomically(tree, input, output);
	}

	private void prefixEnigma(Path input, Path output, String prefix) throws ConversionException {
		MappingModel source = new MappingTreeModelReader().readEnigma(input);
		Map<String, String> classNameMap = classNameMap(source, prefix);
		MappingModel prefixed = new MappingModel();

		for (MappingModel.ClassEntry sourceClass : source.classes()) {
			String prefixedClassName = classNameMap.get(sourceClass.intermediaryName());
			MappingModel.ClassEntry prefixedClass = new MappingModel.ClassEntry(
					prefixedClassName,
					meaningfulClassName(sourceClass.namedName(), prefixedClassName),
					sourceClass.comment());

			for (MappingModel.FieldEntry sourceField : sourceClass.fields()) {
				prefixedClass.addField(new MappingModel.FieldEntry(
						sourceField.intermediaryName(),
						mapDesc(sourceField.intermediaryDesc(), classNameMap),
						sourceField.namedName(),
						sourceField.comment()));
			}

			for (MappingModel.MethodEntry sourceMethod : sourceClass.methods()) {
				MappingModel.MethodEntry prefixedMethod = new MappingModel.MethodEntry(
						sourceMethod.intermediaryName(),
						mapDesc(sourceMethod.intermediaryDesc(), classNameMap),
						sourceMethod.namedName(),
						sourceMethod.comment());

				for (MappingModel.ArgEntry sourceArg : sourceMethod.args()) {
					prefixedMethod.addArg(new MappingModel.ArgEntry(
							sourceArg.lvIndex(),
							sourceArg.namedName(),
							sourceArg.comment()));
				}

				prefixedClass.addMethod(prefixedMethod);
			}

			prefixed.addClass(prefixedClass);
		}

		new EnigmaModelWriter().writeAtomically(prefixed, output);
	}

	private Map<String, String> classNameMap(MappingModel model, String prefix) {
		Map<String, String> map = new LinkedHashMap<>();
		for (MappingModel.ClassEntry classEntry : model.classes()) {
			map.put(classEntry.intermediaryName(), prefixedName(prefix, classEntry.intermediaryName()));
		}

		return map;
	}

	private String mapDesc(String desc, Map<String, String> classNameMap) {
		return MappingUtil.mapDesc(desc, classNameMap);
	}

	private String meaningfulClassName(String namedName, String intermediaryName) {
		return namedName != null && !innermostClassName(namedName).equals(innermostClassName(intermediaryName))
				? namedName
				: null;
	}

	private String innermostClassName(String name) {
		int innerClassIndex = name.lastIndexOf('$');
		int packageIndex = name.lastIndexOf('/');
		int index = Math.max(innerClassIndex, packageIndex);
		return index >= 0 ? name.substring(index + 1) : name;
	}

	private String prefixedName(String prefix, String name) {
		String normalized = Objects.requireNonNull(name, "name");
		if (normalized.startsWith(prefix + "/")) {
			return normalized;
		}

		return prefix + "/" + normalized;
	}

	private String normalizePrefix(String prefix) throws ConversionException {
		if (prefix == null) {
			throw new ConversionException("Prefix must not be empty");
		}

		String normalized = prefix;
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		if (normalized.isEmpty()) {
			throw new ConversionException("Prefix must not be empty");
		}

		if (normalized.startsWith("/")) {
			throw new ConversionException("Prefix must not start with /");
		}

		if (normalized.contains("//")) {
			throw new ConversionException("Prefix must not contain //");
		}

		if (normalized.contains(".")) {
			throw new ConversionException("Prefix must use / separators, not dots");
		}

		return normalized;
	}

	private void requireOutputExtension(String outputName, String extension, Path output) throws ConversionException {
		if (!outputName.endsWith(extension)) {
			throw new ConversionException("Output mapping file must end with " + extension + ": " + output);
		}
	}

	private void writeTinyAtomically(MemoryMappingTree tree, Path input, Path output) throws ConversionException {
		Path absoluteOutput = output.toAbsolutePath();
		Path parent = absoluteOutput.getParent();

		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException e) {
			throw new ConversionException("Failed to create output directory: " + output, e);
		}

		Path temp = createTempFile(parent, absoluteOutput);

		try {
			try (MappingWriter writer = MappingWriter.create(temp, tinyFormat(input))) {
				tree.accept(Objects.requireNonNull(writer));
			}
			replaceOutput(temp, absoluteOutput);
		} catch (IOException e) {
			deleteTemp(temp);
			throw new ConversionException("Failed to write Tiny file: " + output, e);
		} catch (RuntimeException e) {
			deleteTemp(temp);
			throw e;
		}
	}

	private MappingFormat tinyFormat(Path input) throws IOException {
		try (Reader reader = Files.newBufferedReader(input)) {
			char[] header = new char[8];
			int read = reader.read(header);
			String start = read < 0 ? "" : new String(header, 0, read);
			return start.startsWith("v1\t") ? MappingFormat.TINY_FILE : MappingFormat.TINY_2_FILE;
		}
	}

	private Path createTempFile(Path parent, Path absoluteOutput) throws ConversionException {
		try {
			return Files.createTempFile(parent, absoluteOutput.getFileName().toString(), ".tmp");
		} catch (IOException e) {
			throw new ConversionException("Failed to create temporary output file for " + absoluteOutput, e);
		}
	}

	private void replaceOutput(Path temp, Path output) throws IOException {
		try {
			Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteTemp(Path temp) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException ignored) {
		}
	}

	private boolean samePath(Path input, Path output) throws ConversionException {
		try {
			return Files.isSameFile(input, output);
		} catch (IOException e) {
			return input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize());
		}
	}
}
