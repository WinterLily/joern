final trace = <String>[];

Object choose(Object input) {
  try {
    throw input;
  } on FormatException catch (error, stack) {
    trace.add('format');
    return error.message;
  } on StateError catch (error) {
    trace.add('state');
    return error.message;
  } catch (error, stack) {
    trace.add('fallback');
    return error;
  } finally {
    trace.add('finally');
  }
}

String overlapping(Object input) {
  try {
    throw input;
  } on Exception {
    trace.add('first');
    return 'first';
  } on FormatException {
    trace.add('second');
    return 'second';
  }
}

Object filtered(Object input) {
  try {
    throw input;
  } on FormatException catch (error) {
    return error.message;
  }
}

Object nested(Object input) {
  try {
    try {
      throw input;
    } on String catch (error, stack) {
      trace.add('inner');
      rethrow;
    }
  } catch (error, stack) {
    trace.add('outer');
    return error;
  }
}

String preservedReturn(String input) {
  try {
    return input;
  } finally {
    try {
      throw 'cleanup';
    } catch (error) {
      trace.add('handled');
    }
  }
}
