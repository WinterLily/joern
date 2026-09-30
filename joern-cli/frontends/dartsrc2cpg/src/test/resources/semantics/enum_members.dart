enum Simple { first, second }

enum Enhanced {
  first(10),
  second.named(20);

  final int payload;
  const Enhanced(this.payload);
  const Enhanced.named(this.payload);
}

enum Custom {
  first;

  @override
  String toString() => 'custom';
  String original() => super.toString();
}

enum Shadow {
  first;

  String get name => 'shadow';
  final String _name = 'private';
  String privateName() => _name;
}

String describe(Simple value) => '${value.index}:${value.name}:$value';
List<Simple> all() => Simple.values;
String overridden(Custom value) => value.toString();
String named(Shadow value) => value.name;
String originalName(Shadow value) => EnumName(value).name;
String Function() bound(Simple value) => value.toString;
