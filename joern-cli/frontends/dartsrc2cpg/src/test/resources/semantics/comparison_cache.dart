final trace = <String>[];

class Probe {
  final int value;
  const Probe(this.value);

  @override
  bool operator ==(Object other) {
    trace.add('$value==${other is Probe ? other.value : other}');
    return other is Probe && value == other.value;
  }

  bool operator >(Probe other) {
    trace.add('$value>${other.value}');
    return value > other.value;
  }

  @override
  int get hashCode => value;
}

const marker = Probe(1);
const alias = marker;
const other = Probe(2);

String constants(Probe? input, bool accept) => switch (input) {
  marker when accept => 'first',
  alias => 'second',
  _ => 'other',
};

String equal(Probe? input, bool accept) => switch (input) {
  == marker when accept => 'first',
  == alias => 'second',
  _ => 'other',
};

String negation(Probe? input) => switch (input) {
  == marker when false => 'first',
  != alias => 'different',
  == marker => 'same',
  _ => 'other',
};

String directions(Probe? input) => switch (input) {
  marker when false => 'first',
  == alias => 'second',
  _ => 'other',
};

bool logical(Probe input) => switch (input) {
  > marker || > alias => true,
  _ => false,
};

bool distinct(Probe input) => switch (input) {
  > marker when false => true,
  > other when false => true,
  > alias => true,
  _ => false,
};

List<String> separate(Probe? input) {
  final first = switch (input) {
    == marker when false => 'first',
    == alias => 'second',
    _ => 'other',
  };
  final second = switch (input) {
    == marker when false => 'first',
    == alias => 'second',
    _ => 'other',
  };
  return [first, second];
}

bool nested((Probe, Probe) input) => switch (input) {
  (> marker, _) when false => true,
  (_, > marker) => true,
  _ => false,
};
