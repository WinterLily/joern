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

Object caughtValue(Object input) {
  try {
    throw input;
  } catch (error) {
    return error;
  }
}

StackTrace caughtTrace(Object input) {
  try {
    throw input;
  } catch (error, stack) {
    return stack;
  }
}

Object caughtReplacement(Object input) {
  try {
    throw input;
  } catch (error) {
    try {
      throw 'constant';
    } catch (replacement) {
      return replacement;
    }
  }
}

Object forwardedRethrow(Object input) {
  try {
    try {
      throw input;
    } catch (error) {
      rethrow;
    }
  } catch (error) {
    return error;
  }
}

Object independentHandler(Object input) {
  try {
    throw input;
  } catch (error) {
    final ignored = error;
  }
  try {
    throw 'constant';
  } catch (error) {
    return error;
  }
}

class Failure {
  final Object value;
  Failure(this.value);
}

Object caughtConstructed(Object input) {
  try {
    throw Failure(input);
  } catch (error) {
    return error;
  }
}

Object throughFinally(Object input) {
  try {
    try {
      throw input;
    } finally {
      trace.add('normal');
    }
  } catch (error) {
    return error;
  }
}

Object throughHandledCleanup(Object input) {
  try {
    try {
      throw input;
    } finally {
      try {
        throw 'cleanup';
      } catch (local) {
        trace.add(local.toString());
      }
    }
  } catch (error) {
    return error;
  }
}

Object replacedInCleanup(Object input) {
  try {
    try {
      throw input;
    } finally {
      throw 'constant';
    }
  } catch (error) {
    return error;
  }
}

Object returnedFromCleanup(Object input) {
  try {
    try {
      throw input;
    } finally {
      return 'constant';
    }
  } catch (error) {
    return error;
  }
}

Object breakFromCleanup(Object input) {
  try {
    do {
      try {
        throw input;
      } finally {
        break;
      }
    } while (false);
    trace.add('after break');
  } catch (error) {
    return error;
  }
  return 'constant';
}

Object continueFromCleanup(Object input) {
  try {
    outer: do {
      try {
        throw input;
      } finally {
        continue outer;
      }
    } while (false);
    trace.add('after continue');
  } catch (error) {
    return error;
  }
  return 'constant';
}

Object localBreakInCleanup(Object input) {
  try {
    try {
      throw input;
    } finally {
      do {
        break;
      } while (false);
    }
  } catch (error) {
    return error;
  }
}

Object localContinueInCleanup(Object input) {
  try {
    try {
      throw input;
    } finally {
      local: do {
        continue local;
      } while (false);
    }
  } catch (error) {
    return error;
  }
}
