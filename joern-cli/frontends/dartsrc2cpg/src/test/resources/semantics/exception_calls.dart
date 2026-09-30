Object choose(Object input, Object normal, bool fail) {
  if (fail) throw input;
  return normal;
}

Object forward(Object input, Object normal, bool fail) =>
    choose(input, normal, fail);

Object rethrowing(Object input, Object normal, bool fail) {
  try {
    return choose(input, normal, fail);
  } catch (error) {
    rethrow;
  }
}

Object suppress(Object input) {
  try {
    throw input;
  } finally {
    return 'constant';
  }
}

Object handle(Object input) {
  try {
    throw input;
  } catch (error) {
    return 'constant';
  }
}

class Failure {
  final Object value;
  Failure(this.value);
}

Never throwConstructed(Object input) => throw Failure(input);

Object catchForward(Object input, Object normal, bool fail) {
  try {
    return forward(input, normal, fail);
  } catch (error) {
    return error;
  }
}

Object catchRethrow(Object input, Object normal, bool fail) {
  try {
    return rethrowing(input, normal, fail);
  } catch (error) {
    return error;
  }
}

Object catchSuppressed(Object input) {
  try {
    return suppress(input);
  } catch (error) {
    return error;
  }
}

Object catchHandled(Object input) {
  try {
    return handle(input);
  } catch (error) {
    return error;
  }
}

Object catchConstructed(Object input) {
  try {
    throwConstructed(input);
  } catch (error) {
    return error;
  }
}

Object independentCalls(Object input) {
  try {
    choose(input, 'normal', true);
  } catch (error) {
    error.toString();
  }
  try {
    return choose('constant', 'normal', true);
  } catch (error) {
    return error;
  }
}

Object catchStack(Object input) {
  try {
    return choose(input, 'normal', true);
  } catch (error, stack) {
    return stack;
  }
}
