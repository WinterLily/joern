class Shared {
  static String value = 'initial';
  static String other = 'other';
}

class Separate {
  static String value = 'separate';
}

void write(String input) {
  Shared.value = input;
}

String read() => Shared.value;

String direct(String input) {
  Shared.value = input;
  return Shared.value;
}

String nested(String input) {
  write(input);
  return read();
}

String overwritten(String input) {
  write(input);
  write('constant');
  return read();
}

String repeated(String input) {
  write('first');
  write(input);
  return read();
}

String independent(String input) {
  Shared.other = input;
  Shared.value = 'constant';
  return read();
}

String differentOwner(String input) {
  Separate.value = input;
  Shared.value = 'constant';
  return read();
}

String conditional(String input, bool replace) {
  write(input);
  if (replace) write('constant');
  return read();
}

String before(String input) {
  final result = read();
  write(input);
  return result;
}

String looped(String input, bool replace) {
  write(input);
  while (replace) {
    write('constant');
    replace = false;
  }
  return read();
}

String bothBranches(String input, bool choose) {
  write(input);
  if (choose) {
    write('left');
  } else {
    write('right');
  }
  return read();
}

String returnedAfterRead(String input) {
  write(input);
  final first = read();
  write('constant');
  return first;
}

String exchange(String input) {
  write(input);
  return read();
}

String lastCall(String input) {
  exchange('first');
  return exchange(input);
}

String independentCall(String input) {
  exchange(input);
  return exchange('constant');
}

void writeAndThrow(String input) {
  write(input);
  throw StateError('failure');
}

String exceptional(String input) {
  write('before');
  try {
    writeAndThrow(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}
