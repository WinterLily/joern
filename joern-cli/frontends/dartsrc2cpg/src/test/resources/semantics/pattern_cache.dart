final trace = <String>[];

class Probe {
  final String name;
  String value;
  Probe(this.name, this.value);

  String get item {
    trace.add(name);
    final result = value;
    value = 'changed';
    return result;
  }
}

class Pair {
  final Probe left;
  final Probe right;
  Pair(this.left, this.right);
}

String cases(Probe input) => switch (input) {
  Probe(item: 'missing') => 'wrong',
  Probe(item: var value) => value,
};

String guarded(Object? input, bool accept) => switch (input) {
  Probe(item: var value) when accept => value,
  Probe(item: var value) => value,
  _ => 'absent',
};

String nested(Pair input) => switch (input) {
  Pair(left: Probe(item: 'missing')) => 'wrong',
  Pair(right: Probe(item: var right), left: Probe(item: var left)) =>
    '$left:$right',
};

String statements(Probe input) {
  switch (input) {
    case Probe(item: 'missing'):
      return 'wrong';
    case Probe(item: var value):
      return value;
  }
}

bool wildcard(Probe input) => switch (input) {
  Probe(item: _) => true,
};

String logical(Probe input) {
  if (input case Probe(item: != 'missing') && Probe(item: var value)) {
    return value;
  }
  return 'wrong';
}

String separate(Probe input) {
  final first = switch (input) {
    Probe(item: 'missing') => 'wrong',
    Probe(item: var value) => value,
  };
  final second = switch (input) {
    Probe(item: 'missing') => 'wrong',
    Probe(item: var value) => value,
  };
  return '$first:$second';
}

String selected(String input) =>
    switch (Pair(Probe('left', input), Probe('right', 'other'))) {
      Pair(left: Probe(item: 'missing')) => 'wrong',
      Pair(left: Probe(item: var value)) => value,
    };

String independent(String input) =>
    switch (Pair(Probe('left', input), Probe('right', 'other'))) {
      Pair(left: Probe(item: 'missing')) => 'wrong',
      Pair(right: Probe(item: var value)) => value,
    };

String direct(String input) => Probe('left', input).item;
String wrapped(String input) => cases(Probe('left', input));
String nestedDirect(String input) =>
    Pair(Probe('left', input), Probe('right', 'other')).left.item;
