package eu.ponei.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TinyToEnigmaConverterTest {
	@TempDir
	private Path tempDir;

	@Test
	void freshTinyToEnigmaConversion() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tf\tI\tfieldReadable\tfield_1
				\tm\t(Lcom/example/Readable;)V\tuseMe\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class123 com/example/Readable"));
		assertTrue(enigma.contains("FIELD field_1 fieldReadable I"));
		assertTrue(enigma.contains("METHOD method_1 useMe (LClass123;)V"));
	}

	@Test
	void obfuscatedOfficialNamesStayUnmapped() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\ta/b/x\tClass123
				\tf\tI\tb\tfield_1
				\tm\t()V\ta\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class123\n"));
		assertTrue(enigma.contains("FIELD field_1 I"));
		assertTrue(enigma.contains("METHOD method_1 ()V"));
		assertFalse(enigma.contains("a/b/x"));
		assertFalse(enigma.contains("FIELD field_1 b I"));
		assertFalse(enigma.contains("METHOD method_1 a ()V"));
	}

	@Test
	void existingManualNamesSurviveOfficialNameChanges() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tf\tI\tfieldReadable\tfield_1
				\tm\t()V\tmethodReadable\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		new TinyToEnigmaConverter().convert(input, output);
		String edited = Files.readString(output)
				.replace("com/example/Readable", "human/readable/Name")
				.replace(" fieldReadable I", " readableField I")
				.replace(" methodReadable ()V", " readableMethod ()V");
		Files.writeString(output, edited);

		Files.writeString(input, """
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Changed\tClass123
				\tf\tI\tfieldChanged\tfield_1
				\tm\t()V\tmethodChanged\tmethod_1
				""");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class123 human/readable/Name"));
		assertTrue(enigma.contains("FIELD field_1 readableField I"));
		assertTrue(enigma.contains("METHOD method_1 readableMethod ()V"));
		assertFalse(enigma.contains("com/example/Changed"));
	}

	@Test
	void removedTinyEntriesDisappear() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tf\tI\tfieldReadable\tfield_1
				c\tcom/example/OtherReadable\tClass456
				""");
		Path output = tempDir.resolve("output.enigma");
		new TinyToEnigmaConverter().convert(input, output);

		Files.writeString(input, """
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				""");
		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class123 com/example/Readable"));
		assertFalse(enigma.contains("field_1"));
		assertFalse(enigma.contains("Class456"));
	}

	@Test
	void uniqueDescriptorChangePreservesExistingName() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t()V\tmethodReadable\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		new TinyToEnigmaConverter().convert(input, output);
		Files.writeString(output, Files.readString(output).replace(" methodReadable ()V", " readableMethod ()V"));

		Files.writeString(input, """
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t(I)V\tmethodReadable\tmethod_1
				""");

		new TinyToEnigmaConverter().convert(input, output);

		assertTrue(Files.readString(output).contains("METHOD method_1 readableMethod (I)V"));
	}

	@Test
	void ambiguousDescriptorChangeFailsWithoutOverwriting() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t()V\ta\tmethod_1
				\tm\t(I)V\tb\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		new TinyToEnigmaConverter().convert(input, output);
		String before = Files.readString(output);

		Files.writeString(input, """
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t(J)V\tc\tmethod_1
				""");

		assertThrows(ConversionException.class, () -> new TinyToEnigmaConverter().convert(input, output));
		assertEquals(before, Files.readString(output));
	}

	@Test
	void invalidNamespaceSetFails() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tnamed
				c\tcom/example/x\tClass123
				""");

		ConversionException thrown = assertThrows(
				ConversionException.class,
				() -> new TinyToEnigmaConverter().convert(input, tempDir.resolve("output.enigma")));
		assertTrue(thrown.getMessage().contains("official and intermediary"));
	}

	@Test
	void cliHandlesUnknownCommandAndWrongArgCount() {
		Main main = new MainForTest().main();

		assertEquals(2, main.run(java.util.List.of("nope"), out(), err()));
		assertEquals(2, main.run(java.util.List.of("tiny-to-enigma", "only-input.tiny"), out(), err()));
	}

	@Test
	void shadowJarProducesExecutableJar() throws Exception {
		Path jar = Path.of("build/libs/converter-all.jar");
		assertTrue(Files.isRegularFile(jar), "shadow jar should be built before tests");

		try (JarFile jarFile = new JarFile(jar.toFile())) {
			assertEquals("eu.ponei.converter.Main", jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
			assertTrue(jarFile.stream().anyMatch(entry -> entry.getName().equals("net/fabricmc/mappingio/MappingReader.class")));
		}
	}

	private Path tiny(String content) throws Exception {
		Path input = tempDir.resolve("input.tiny");
		Files.writeString(input, content, StandardCharsets.UTF_8);
		return input;
	}

	private PrintStream out() {
		return new PrintStream(new ByteArrayOutputStream());
	}

	private PrintStream err() {
		return new PrintStream(new ByteArrayOutputStream());
	}

	private static final class MainForTest {
		private Main main() {
			return new Main(java.util.List.of(new TinyToEnigmaCommand()));
		}
	}
}
