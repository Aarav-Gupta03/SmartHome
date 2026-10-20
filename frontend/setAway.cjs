const { MongoClient } = require('mongodb'); 
async function run() { 
  const client = new MongoClient(process.env.MONGODB_URI); 
  await client.connect(); 
  await client.db('SmartHomeDB').collection('home_status').updateOne({_id:'singleton'}, {$set:{mode:'AWAY'}}); 
  console.log("Set to AWAY mode");
  await client.close(); 
} 
run();
