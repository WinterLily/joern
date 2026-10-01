final trace = <String>[];

T forward<T>(T value, T ignored) => value;
T reverse<T>(T value, T ignored) => ignored;
T named<T>({required T value, T? ignored}) => value;

class Receiver {
  final String tag;
  Receiver(this.tag);

  T echo<T>(T value, T ignored) {
    trace.add('$tag:$T');
    return value;
  }

  static T select<T>(T value, T ignored) => value;
}

Receiver obtain(String tag) {
  trace.add('obtain:$tag');
  return Receiver(tag);
}

String top(String input, String ignored) {
  final callback = forward<String>;
  return callback(input, ignored);
}

int integers(int input, int ignored) {
  final callback = forward<int>;
  return callback(input, ignored);
}

String staticMethod(String input, String ignored) {
  final callback = Receiver.select<String>;
  return callback(input, ignored);
}

String aliases(String input, String ignored) {
  final generic = forward;
  final specialized = generic<String>;
  final callback = (specialized);
  return callback(input, ignored);
}

String bound(String input, String ignored) {
  final callback = obtain('bound').echo<String>;
  return callback(input, ignored);
}

String namedAliases(String input, String ignored) {
  final generic = named;
  final specialized = generic<String>;
  final callback = (specialized);
  return callback(ignored: ignored, value: input);
}

String defaultAliases(String input, String ignored) {
  final generic = named;
  final callback = generic<String>;
  return callback(value: input);
}

String boundAliases(String input, String ignored) {
  final generic = obtain('alias').echo;
  final specialized = generic<String>;
  final callback = (specialized);
  return callback(input, ignored);
}

String mutable(String input, String ignored, bool change) {
  var generic = forward;
  if (change) generic = reverse;
  final callback = generic<String>;
  return callback(input, ignored);
}

String conditional(String input, String ignored, bool first) {
  final callback = first ? forward<String> : reverse<String>;
  return callback(input, ignored);
}

String Function(String, String) factory(
  String Function(String, String) unused,
) => reverse<String>;

String returned(String input, String ignored) {
  final callback = factory(forward<String>);
  return callback(input, ignored);
}
