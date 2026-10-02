typedef Select = String Function(String, String);
typedef BoundSelect = String Function(String, {String? ignored});
typedef NamedSelect = String Function({required String value, String? ignored});
final events = <String>[];

String pick(String value, String ignored) {
  events.add('pick');
  return value;
}

String fixed(String value, String ignored) {
  events.add('fixed');
  return 'constant';
}

T generic<T>(T value, T ignored) => value;
String named({required String value, String? ignored}) => value;
Select chosen(bool flag) => flag ? pick : fixed;
Select returned(Select callback) => callback;

class Receiver {
  Receiver(this.value);
  final String value;

  String select(String input, {String? ignored}) {
    events.add('select:$value');
    return input;
  }

  String stored(String input, String ignored) => value;
}

String argument(String name, String value) {
  events.add(name);
  return value;
}

Receiver obtain(String value) {
  events.add('receiver:$value');
  return Receiver(value);
}

String castAlias(String input, String ignored) {
  final invoke = pick as Select;
  return invoke(input, ignored);
}

String assertedAlias(String input, String ignored) {
  final invoke = pick!;
  return invoke(input, ignored);
}

String aliasChain(String input, String ignored) {
  final original = pick;
  final invoke = (original as Select)!;
  return invoke(input, ignored);
}

String directCast(String input, String ignored) =>
    (pick as Select)(input, ignored);
String directAssert(String input, String ignored) => pick!(input, ignored);

String directPattern(String input, String ignored) {
  if (pick case final invoke) return invoke(input, ignored);
  throw StateError('unmatched');
}

String castPattern(String input, String ignored) {
  if (pick case final invoke as Select) return invoke(input, ignored);
  throw StateError('unmatched');
}

String assertedPattern(String input, String ignored) {
  if (pick case final invoke!) return invoke(input, ignored);
  throw StateError('unmatched');
}

String declaredPattern(String input, String ignored) {
  final (invoke as Select) = pick;
  return invoke(input, ignored);
}

String genericAlias(String input, String ignored) {
  final invoke = (generic<String>) as Select;
  return invoke(input, ignored);
}

String genericPattern(String input, String ignored) {
  if ((generic<String>) case final invoke as Select) {
    return invoke(input, ignored);
  }
  throw StateError('unmatched');
}

String namedAlias(String input, String ignored) {
  final invoke = named as NamedSelect;
  return invoke(ignored: ignored, value: input);
}

String defaultPattern(String input, String ignored) {
  if (named case final invoke as NamedSelect) return invoke(value: input);
  throw StateError('unmatched');
}

String boundAlias(String input, String ignored) {
  final invoke = obtain('bound').select as BoundSelect;
  return invoke(
    ignored: argument('ignored', ignored),
    argument('input', input),
  );
}

String boundPattern(String input, String ignored) {
  if (obtain('pattern').select case final invoke!) {
    return invoke(input);
  }
  throw StateError('unmatched');
}

String capturedPattern(String input, String ignored) {
  var receiver = obtain(input);
  if (receiver.stored case final invoke as Select) {
    receiver = obtain(ignored);
    return invoke('constant', ignored);
  }
  throw StateError('unmatched');
}

String repeatedBound(String input, String ignored) {
  if (obtain('repeated').select case final invoke!) {
    invoke(ignored);
    return invoke(input);
  }
  throw StateError('unmatched');
}

String conditionalAlias(String input, String ignored, bool flag) {
  final invoke = (flag ? pick : fixed) as Select;
  return invoke(input, ignored);
}

String returnedAlias(String input, String ignored, bool flag) {
  final invoke = chosen(flag) as Select;
  return invoke(input, ignored);
}

String nestedReturnedAlias(String input, String ignored) {
  final invoke = returned(pick as Select) as Select;
  return invoke(input, ignored);
}

String parameterAlias(String input, String ignored, Select callback) {
  final invoke = callback as Select;
  return invoke(input, ignored);
}

String mutableAlias(String input, String ignored, bool flag) {
  var invoke = pick as Select;
  if (!flag) invoke = fixed;
  return invoke(input, ignored);
}

String mutablePattern(String input, String ignored, bool flag) {
  if (pick case var invoke as Select) {
    if (!flag) invoke = fixed;
    return invoke(input, ignored);
  }
  throw StateError('unmatched');
}

String joinedPattern(String input, String ignored) {
  if ((pick, fixed) case (final invoke, _) || (_, final invoke)) {
    return invoke(input, ignored);
  }
  throw StateError('unmatched');
}

class Holder {
  Holder(this.invoke);
  final Select invoke;
}

String joinedSame(String input, String ignored) {
  if (pick case (final invoke as Select) || final invoke) {
    return invoke(input, ignored);
  }
  throw StateError('unmatched');
}

String joinedMixed(String input, String ignored) {
  if ((pick as Object) case (final invoke as Select) || Holder(:final invoke)) {
    return invoke(input, ignored);
  }
  throw StateError('unmatched');
}

typedef Creation = Created Function(String, {String ignored});

class Created {
  Created(this.value, {String ignored = 'fallback'}) {
    events.add('create:$ignored');
  }
  final String value;
}

String constructorCast(String input, String ignored) =>
    (Created.new as Creation)(input, ignored: ignored).value;

String constructorAssert(String input, String ignored) =>
    Created.new!(input).value;

String constructorAlias(String input, String ignored) {
  final invoke = Created.new as Creation;
  return invoke(input, ignored: ignored).value;
}

String constructorFailure(String input) =>
    (Created.new as String Function(String))(argument('input', input));
