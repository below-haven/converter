package eu.ponei.converter;

final class ConversionException extends Exception {
	ConversionException(String message) {
		super(message);
	}

	ConversionException(String message, Throwable cause) {
		super(message, cause);
	}
}
