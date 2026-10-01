final trace = <String>[];

abstract class Surface {
  Object echo(Object value, Object ignored);
  T generic<T>(T value, T ignored);
  Object get read;
  set write(Object value);
  Object operator +(Object value);
  Object operator [](Object value);
}

class Concrete implements Surface {
  String stored = '';
  @override
  String echo(covariant String value, Object ignored) {
    trace.add('concrete:echo');
    return value;
  }

  @override
  U generic<U>(U value, U ignored) {
    trace.add('concrete:generic');
    return value;
  }

  @override
  String get read => stored;
  @override
  set write(covariant String value) {
    stored = value;
  }

  @override
  String operator +(Object value) => value as String;
  @override
  String operator [](Object value) => value as String;
}

mixin Layer implements Surface {
  @override
  String echo(covariant String value, Object ignored) {
    trace.add('layer:echo');
    return value;
  }

  @override
  V generic<V>(V value, V ignored) {
    trace.add('layer:generic');
    return value;
  }
}

class Mixed extends Concrete with Layer {}

class Descendant extends Concrete {}

class Unrelated {
  String echo(String value, String ignored) => ignored;
}

Object invoke(Surface receiver, String input, String ignored) =>
    receiver.echo(input, ignored);
String generic(Surface receiver, String input, String ignored) =>
    receiver.generic<String>(input, ignored);
Object bound(Surface receiver, String input, String ignored) {
  final callback = receiver.echo;
  return callback(input, ignored);
}

String genericBound(Surface receiver, String input, String ignored) {
  final callback = receiver.generic<String>;
  return callback(input, ignored);
}

String concrete(Concrete receiver, String input, String ignored) =>
    receiver.echo(input, ignored);
Object getter(Surface receiver, String input) {
  receiver.write = input;
  return receiver.read;
}

Object plus(Surface receiver, String input) => receiver + input;
Object index(Surface receiver, String input) => receiver[input];
