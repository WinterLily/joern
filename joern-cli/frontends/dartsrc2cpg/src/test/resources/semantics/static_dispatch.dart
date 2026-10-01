final trace = <String>[];

class Base {
  String stored = '';
  String echo(String value, String ignored) => value;
  String get read => stored;
  set write(String value) {
    stored = value;
  }

  String operator +(String value) => value;
  String operator [](String value) => value;
  void operator []=(String key, String value) {
    stored = value;
  }

  static T select<T>(T value, T ignored) => value;
}

class Derived extends Base {
  @override
  String echo(String value, String ignored) => ignored;
  @override
  String get read => 'derived';
  @override
  set write(String value) {
    trace.add('derived:write');
  }

  @override
  String operator +(String value) => 'derived';
  @override
  String operator [](String value) => 'derived';
  @override
  void operator []=(String key, String value) {
    trace.add('derived:[]=');
  }

  static T select<T>(T value, T ignored) => ignored;

  String directSuper(String input, String ignored) =>
      super.echo(input, ignored);
  String tearoffSuper(String input, String ignored) {
    final callback = super.echo;
    return callback(input, ignored);
  }

  String invokeSuper(String input, String ignored) =>
      (super.echo)(input, ignored);
  String getterSuper(String input) {
    stored = input;
    return super.read;
  }

  String setterSuper(String input) {
    super.write = input;
    return stored;
  }

  String operatorSuper(String input) => super + input;
  String indexSuper(String input) => super[input];
  String indexSetSuper(String input) {
    super['key'] = input;
    return stored;
  }
}

class Payload {}

extension View on Payload {
  T echo<T>(T value, T ignored) => value;
  set write(String value) {
    trace.add('extension:$value');
  }
}

String staticTearoff(String input, String ignored) {
  final callback = Base.select<String>;
  return callback(input, ignored);
}

String extensionTearoff(String input, String ignored) {
  final callback = Payload().echo<String>;
  return callback(input, ignored);
}

String extensionInvoke(String input, String ignored) =>
    (Payload().echo<String>)(input, ignored);

void extensionSetter(String input) {
  Payload().write = input;
}

String virtual(Base receiver, String input, String ignored) =>
    receiver.echo(input, ignored);
