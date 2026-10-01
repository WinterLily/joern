final trace = <String>[];

class Comparison {
  final String value;
  const Comparison(this.value);

  @override
  bool operator ==(Object other) {
    trace.add('$value==${other is Comparison ? other.value : other}');
    return other is Comparison && value == other.value;
  }

  bool operator >(Comparison other) {
    trace.add('$value>${other.value}');
    return value.compareTo(other.value) > 0;
  }

  @override
  int get hashCode => value.hashCode;
}

const marker = Comparison('constant');

bool constantPattern(Comparison? input) => switch (input) {
  marker => true,
  _ => false,
};

bool equalPattern(Comparison? input) => switch (input) {
  == marker => true,
  _ => false,
};

bool unequalPattern(Comparison? input) => switch (input) {
  != marker => true,
  _ => false,
};

bool greaterPattern(Comparison input) => switch (input) {
  > marker => true,
  _ => false,
};

bool binaryEqual(Comparison? input) => input == marker;
bool binaryUnequal(Comparison? input) => input != marker;
bool nullPattern(Comparison? input) => switch (input) {
  null => true,
  _ => false,
};
bool equalNullPattern(Comparison? input) => switch (input) {
  == null => true,
  _ => false,
};
bool binaryNull(Comparison? input) => input == null;

Comparison? marked(String label, Comparison? value) {
  trace.add(label);
  return value;
}

bool ordered(Comparison? left, Comparison? right) =>
    marked('left', left) == marked('right', right);

bool dynamicEqual(dynamic left, dynamic right) => left == right;
bool dynamicUnequal(dynamic left, dynamic right) => left != right;
