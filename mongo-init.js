db = db.getSiblingDB('bankx');

// Crear colecciones si no existen
db.createCollection('accounts');
db.createCollection('transactions');

// Crear índices
db.accounts.createIndex({ accountNumber: 1 }, { unique: true });
db.transactions.createIndex({ accountNumber: 1 });
db.transactions.createIndex({ createdAt: -1 });

// Insertar cuentas de prueba
db.accounts.insertMany([
  {
    accountNumber: "001-0001",
    holderName: "John Doe",
    balance: NumberDecimal("1000.00"),
    status: "ACTIVE",
    createdAt: new Date()
  },
  {
    accountNumber: "001-0002",
    holderName: "Jane Smith",
    balance: NumberDecimal("500.00"),
    status: "ACTIVE",
    createdAt: new Date()
  }
]);

print("Database initialized with accounts and transaction collections");
