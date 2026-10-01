class Base {
  String _pick(String value, String ignored) => value;
  String echo(String value, String ignored) => value;
  String invoke(String value, String ignored) => _pick(value, ignored);
}

class Branch extends Base {}

class SubBranch extends Branch {
  @override
  String echo(String value, String ignored) => value;
}

class Sibling extends Base {
  @override
  String echo(String value, String ignored) => ignored;
}

String narrow(Branch receiver, String input, String ignored) =>
    receiver.echo(input, ignored);
String boundNarrow(Branch receiver, String input, String ignored) {
  final callback = receiver.echo;
  return callback(input, ignored);
}
