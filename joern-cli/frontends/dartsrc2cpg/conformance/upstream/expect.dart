class Expect {
  static void equals(Object? expected, Object? actual) {
    if (expected != actual) {
      throw StateError('Expected $expected, got $actual');
    }
  }

  static void listEquals(List<Object?> expected, List<Object?> actual) {
    equals(expected.length, actual.length);
    for (var index = 0; index < expected.length; index++) {
      equals(expected[index], actual[index]);
    }
  }

  static void throws(void Function() action) {
    try {
      action();
    } catch (_) {
      return;
    }
    throw StateError('Expected an exception');
  }
}
