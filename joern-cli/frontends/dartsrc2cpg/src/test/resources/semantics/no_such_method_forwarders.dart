abstract class Api {
  String transform(String value);
}

class Forwarding implements Api {
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      invocation.positionalArguments.first;
}

class Constant implements Api {
  @override
  dynamic noSuchMethod(Invocation invocation) => 'constant';
}

abstract class RichApi<T> {
  T pick(T value, {String label = 'interface'});
  E echo<E>(E value);
  String get title;
  set title(String value);
}

abstract class Handler implements RichApi<String> {
  final List<Invocation> invocations = [];
  String stored = 'initial';

  @override
  dynamic noSuchMethod(Invocation invocation) {
    invocations.add(invocation);
    if (invocation.isGetter) return stored;
    if (invocation.isSetter) {
      stored = invocation.positionalArguments.first as String;
      return null;
    }
    return invocation.positionalArguments.first;
  }
}

class Inherited extends Handler {}

class Concrete extends Inherited {
  @override
  String pick(String value, {String label = 'concrete'}) => 'concrete';
}

String forwarding(String input) => Forwarding().transform(input);
String constant(String input) => Constant().transform(input);
String bound(String input) {
  final callback = Forwarding().transform;
  return callback(input);
}

String generic(String input) => Inherited().echo<String>(input);
String picked(String input) => Inherited().pick(input);
String supplied(String input) => Inherited().pick(input, label: 'supplied');
String concrete(String input) => Concrete().pick(input);
