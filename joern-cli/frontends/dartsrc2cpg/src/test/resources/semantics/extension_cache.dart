final trace = <String>[];

class Box<T> {
  final T value;
  Box(this.value);
}

extension View<T> on Box<T> {
  String get kind {
    trace.add('kind:$T');
    return '$T';
  }

  bool operator >(int other) {
    trace.add('greater:$T:$other');
    return value is num && (value as num) > other;
  }
}

String repeated(Box<int> input) => switch (input) {
  Box<int>(kind: 'missing') => 'wrong',
  Box<int>(kind: var value) => value,
};

String substituted(Box<int> input) => switch (input) {
  Box<int>(kind: 'missing') => 'wrong',
  Box<num>(kind: var value) => value,
};

bool compared(Box<int> input) => switch (input) {
  > 1 when false => true,
  > 1 => true,
  _ => false,
};
