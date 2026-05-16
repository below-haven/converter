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
	void obfuscatedOfficialFieldsAreSkippedButClassesAndMethodsStayUnmapped() throws Exception {
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
		assertTrue(enigma.contains("METHOD method_1 ()V"));
		assertFalse(enigma.contains("a/b/x"));
		assertFalse(enigma.contains("field_1"));
		assertFalse(enigma.contains("METHOD method_1 a ()V"));
	}

	@Test
	void obfuscatedOfficialFieldsKeepExistingManualEnigmaNames() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tf\tI\tb\tfield_1
				\tm\t()V\ta\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		Files.writeString(output, """
				CLASS Class123 com/example/Readable
					FIELD field_1 oldField I
					METHOD method_1 oldMethod ()V
				""");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class123 com/example/Readable"));
		assertTrue(enigma.contains("FIELD field_1 oldField I"));
		assertTrue(enigma.contains("METHOD method_1 oldMethod ()V"));
	}

	@Test
	void existingManualEnigmaArgsSurviveTinyWithoutArgs() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t(Ljava/lang/String;I)V\tmethodReadable\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		MappingModel model = new MappingModel();
		MappingModel.ClassEntry classEntry = new MappingModel.ClassEntry("Class123", "com/example/Readable", null);
		MappingModel.MethodEntry methodEntry = new MappingModel.MethodEntry(
				"method_1",
				"(Ljava/lang/String;I)V",
				"oldMethod",
				null);
		methodEntry.addArg(new MappingModel.ArgEntry(2, "count", null));
		methodEntry.addArg(new MappingModel.ArgEntry(1, "text", null));
		classEntry.addMethod(methodEntry);
		model.addClass(classEntry);
		new EnigmaModelWriter().writeAtomically(model, output);

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("METHOD method_1 oldMethod (Ljava/lang/String;I)V"));
		assertTrue(enigma.contains("text"));
		assertTrue(enigma.contains("count"));
		assertOrder(enigma, "text", "count");
	}

	@Test
	void existingEnigmaConstructorsSurviveTinyWithoutConstructors() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tm\t()V\tmethodReadable\tmethod_1
				""");
		Path output = tempDir.resolve("output.enigma");
		MappingModel model = new MappingModel();
		MappingModel.ClassEntry classEntry = new MappingModel.ClassEntry("Class123", "com/example/Readable", null);
		MappingModel.MethodEntry constructor = new MappingModel.MethodEntry(
				"<init>",
				"(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
				null,
				null);
		constructor.addArg(new MappingModel.ArgEntry(1, "username", null));
		constructor.addArg(new MappingModel.ArgEntry(2, "password", null));
		constructor.addArg(new MappingModel.ArgEntry(3, "encrypted", null));
		classEntry.addMethod(constructor);
		model.addClass(classEntry);
		new EnigmaModelWriter().writeAtomically(model, output);

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("METHOD <init> (Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"));
		assertTrue(enigma.contains("ARG 1 username"));
		assertTrue(enigma.contains("ARG 2 password"));
		assertTrue(enigma.contains("ARG 3 encrypted"));
		assertTrue(enigma.contains("METHOD method_1 methodReadable ()V"));
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
	void unmappedInnerClassesUnderMappedOuterClassesStayUnmapped() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Outer\tClass_182
				c\tcom/example/Outer$A\tClass_182$Class_183
				""");
		Path output = tempDir.resolve("output.enigma");
		Files.writeString(output, """
				CLASS Class_182 com/example/Outer
					CLASS Class_183
				""");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		assertTrue(enigma.contains("CLASS Class_182 com/example/Outer"));
		assertTrue(enigma.contains("\tCLASS Class_183\n"));
		assertFalse(enigma.contains("\tCLASS Class_183 Class_183"));
		assertFalse(enigma.contains("com/example/Outer$Class_183"));
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
	void membersAreWrittenInSourceOrderAfterMerge() throws Exception {
		Path input = tiny("""
				tiny\t2\t0\tofficial\tintermediary
				c\tcom/example/Readable\tClass123
				\tf\tI\tfieldTwo\tfield_2
				\tf\tZ\tfieldOneBoolean\tfield_1
				\tf\tI\tfieldOneInt\tfield_1
				\tf\tI\tfieldThree\tfield_3
				""");
		Path output = tempDir.resolve("output.enigma");
		Files.writeString(output, """
				CLASS Class123 com/example/Readable
					FIELD field_2 oldTwo I
					FIELD field_1 oldBoolean Z
					FIELD field_1 oldInt I
				""");

		new TinyToEnigmaConverter().convert(input, output);

		String enigma = Files.readString(output);
		int fieldOneInt = enigma.indexOf("FIELD field_1 oldInt I");
		int fieldOneBoolean = enigma.indexOf("FIELD field_1 oldBoolean Z");
		int fieldTwo = enigma.indexOf("FIELD field_2 oldTwo I");
		int fieldThree = enigma.indexOf("FIELD field_3 fieldThree I");
		assertTrue(fieldOneInt > 0);
		assertTrue(fieldOneInt < fieldOneBoolean);
		assertTrue(fieldOneBoolean < fieldTwo);
		assertTrue(fieldTwo < fieldThree);
	}

	@Test
	void writerOrdersClassesMembersAndLocalsBySource() throws Exception {
		MappingModel model = new MappingModel();
		MappingModel.ClassEntry classB = new MappingModel.ClassEntry("ClassB", "com/example/B", null);
		MappingModel.ClassEntry class1001 = new MappingModel.ClassEntry("Class1001", "com/example/Class1001", null);
		MappingModel.ClassEntry class101 = new MappingModel.ClassEntry("Class101", "com/example/Class101", null);
		MappingModel.ClassEntry class100 = new MappingModel.ClassEntry("Class100", "com/example/Class100", null);
		classB.addField(new MappingModel.FieldEntry("field_z", "J", "fieldZ", null));
		classB.addField(new MappingModel.FieldEntry("field_a", "Z", "fieldABoolean", null));
		classB.addField(new MappingModel.FieldEntry("field_a", "I", "fieldAInt", null));
		classB.addMethod(new MappingModel.MethodEntry("method_z", "()V", "methodZ", null));
		MappingModel.MethodEntry methodAObject = new MappingModel.MethodEntry("method_a", "(Ljava/lang/String;)V", "methodAObject", null);
		methodAObject.addArg(new MappingModel.ArgEntry(3, "localThree", null));
		methodAObject.addArg(new MappingModel.ArgEntry(1, "localOne", null));
		classB.addMethod(methodAObject);
		classB.addMethod(new MappingModel.MethodEntry("method_a", "(I)V", "methodAInt", null));
		model.addClass(classB);
		model.addClass(class1001);
		model.addClass(class101);
		model.addClass(class100);
		model.addClass(new MappingModel.ClassEntry("ClassA", "com/example/A", null));
		Path output = tempDir.resolve("output.enigma");

		new EnigmaModelWriter().writeAtomically(model, output);

		String enigma = Files.readString(output);
		assertOrder(enigma, "CLASS Class100", "CLASS Class101", "CLASS Class1001", "CLASS ClassA", "CLASS ClassB");
		assertOrder(enigma,
				"FIELD field_a fieldAInt I",
				"FIELD field_a fieldABoolean Z",
				"FIELD field_z fieldZ J");
		assertOrder(enigma,
				"METHOD method_a methodAInt (I)V",
				"METHOD method_a methodAObject (Ljava/lang/String;)V",
				"METHOD method_z methodZ ()V");
		assertOrder(enigma, "localOne", "localThree");
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

	private void assertOrder(String text, String first, String... rest) {
		int previous = text.indexOf(first);
		assertTrue(previous >= 0, first);

		for (String value : rest) {
			int current = text.indexOf(value);
			assertTrue(current >= 0, value);
			assertTrue(previous < current, first + " should come before " + value);
			previous = current;
		}
	}

	private static final class MainForTest {
		private Main main() {
			return new Main(java.util.List.of(new TinyToEnigmaCommand()));
		}
	}
}
