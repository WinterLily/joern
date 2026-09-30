String capturedValue(String input) {
  String callback() => input;
  return callback();
}

String unrelatedLocal(String input) {
  String callback() {
    input.length;
    final result = 'constant';
    return result;
  }

  return callback();
}

String overwrittenCapture(String input) {
  String callback() {
    input = 'constant';
    return input;
  }

  return callback();
}

String conditionalCapture(String input, bool replace) {
  String callback() {
    if (replace) input = 'constant';
    return input;
  }

  return callback();
}

String nestedCapture(String input) {
  String callback() {
    String inner() => input;
    return inner();
  }

  return callback();
}

String shadowedCapture(String input) {
  String callback() {
    input.length;
    {
      final input = 'constant';
      return input;
    }
  }

  return callback();
}

String localCapture(String input) {
  final captured = input;
  String callback() => captured;
  return callback();
}

String localOverwrite(String input) {
  var captured = input;
  String callback() {
    captured = 'constant';
    return captured;
  }

  return callback();
}
