// Optional UI regression: TAXILINK_PLAYWRIGHT points to playwright-core; TAXILINK_TEST_CHROME selects Chrome.
const { chromium } = require(process.env.TAXILINK_PLAYWRIGHT || 'playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({executablePath: process.env.TAXILINK_TEST_CHROME, headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1200,height:1000}});
    page.setDefaultTimeout(7000);
    const errors = [], requests = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    let centers = {}, ride;
    const geometry = {progressMeters:40,totalMeters:90,points:[[0,0,0,0],[30,30,0,0],[60,60,0,20],[90,90,0,0]],legs:[
      {stage:'TO_PICKUP',startMeters:0,endMeters:30}, {stage:'ON_TRIP',startMeters:30,endMeters:60}, {stage:'RETURNING',startMeters:60,endMeters:90}]};
    const cars = [{vehicleId:'minecart',capacity:2,length:1,width:1,bogie1Position:0,bogie2Position:0,couplingPadding1:0,couplingPadding2:0}];
    await page.route('http://127.0.0.1:8789/**',async route => {
      const pathname = new URL(route.request().url()).pathname;
      if (pathname==='/') return route.fulfill({contentType:'text/html; charset=utf-8',body:fs.readFileSync(path.join(__dirname,'../webman/app/process/taxilink-debug.html'),'utf8')});
      assert.equal(route.request().headers().authorization,'Bearer ui-only');
      if (pathname==='/health') return route.fulfill({json:{serverId:'taxilink-01',connected:true,pending:0}});
      if (pathname==='/events') return route.fulfill({json:{cursor:0,events:[]}});
      const {type,params,dimension}=route.request().postDataJSON(); requests.push(type);
      assert.equal(dimension,'minecraft/overworld'); let data;
      switch(type) {
        case 'taxi.centers.put': centers[params.centerId]=params.center; data={centers}; break;
        case 'taxi.centers.list': data={centers}; break;
        case 'taxi.quote':
          assert.equal(params.centerId,'city');assert.equal(params.centerRouteId,'airport');
          assert.equal(params.depotId,undefined);assert.equal(params.pickupPlatformId,undefined);
          data={quoteId:'quote-test',fare:{currency:'CNY',amountMinor:600},distanceMeters:30,estimatedSeconds:10,geometry};break;
        case 'taxi.create':
          assert.equal(params.quoteId,'quote-test');
          ride={orderId:params.orderId,phase:'ACTIVE',stage:'ON_TRIP',stageLabel:'行程中',plate:'TX-TEST',capacity:2,onboardCount:1,controlMode:'automatic',passengers:[{uuid:'player-uuid',playerName:'BG7QVU',car:0,boardedAt:1700000000000}]};data=ride;break;
        case 'taxi.status':data=ride;break;
        case 'taxi.list':data={rides:[ride]};break;
        case 'taxi.passengers':data={...ride,passengerHistory:[{uuid:'player-uuid',playerName:'BG7QVU',car:0,boardedAt:1700000000000,alightedAt:1700000010000}],historyTotal:1};break;
        case 'taxi.logs':data={items:[{timestamp:1700000000000,type:'ride.dispatched'},{timestamp:1700000010000,type:'passenger.boarded',detail:{playerName:'BG7QVU',uuid:'player-uuid'}}],total:2,nextOffset:null};break;
        case 'taxi.geometry':data=geometry;break;
        case 'taxi.control':ride.controlMode=params.mode==='resume'?'automatic':params.mode;data=ride;break;
        case 'taxi.depart':case 'taxi.doors':data=ride;break;
        case 'taxi.destroy':data={...ride,phase:'DESTROYED',stageLabel:'已销毁'};break;
        default:throw new Error('Unhandled command '+type);
      }
      return route.fulfill({json:{ok:true,data}});
    });
    await page.goto('http://127.0.0.1:8789/');
    await page.locator('#token').fill('ui-only');await page.locator('#connect').click();
    await page.getByText('网关鉴权成功',{exact:true}).waitFor();
    await page.locator('#editCenterId').fill('city');
    await page.locator('#centerJson').fill(JSON.stringify({name:'城区中心',depotIds:['1'],routes:[{id:'airport',name:'机场接送',pickupPlatformId:'10',dropoffPlatformId:'20'}]}));
    await page.locator('#saveCenter').click();await page.getByText('车辆中心已加载',{exact:true}).waitFor();
    await page.locator('#centerRows button').click();await page.locator('#centerRouteId').fill('airport');
    await page.locator('#vehicleCars').fill(JSON.stringify(cars));await page.locator('#passengerCount').fill('2');
    await page.locator('#quote').click();await page.getByText('报价已计算，可以确认发单',{exact:true}).waitFor();
    await page.getByRole('button',{name:'确认发单',exact:true}).click();await page.getByText('订单已受理，可查询状态或查看发车事件',{exact:true}).waitFor();
    assert.match(await page.locator('#orderSummary').innerText(),/行程中/);
    await page.locator('#passengers').click();await page.getByText('操作完成',{exact:true}).waitFor();
    assert.match(await page.locator('#passengerVisits').innerText(),/BG7QVU.*上车.*下车/);
    await page.locator('#loadOrderLogs').click();await page.getByText('已读取订单动态',{exact:true}).waitFor();
    assert.match(await page.locator('#orderLogs').innerText(),/车辆出库/);
    for (const button of ['setSpeed','emergency','resume']) {
      await page.locator('#'+button).click();await page.getByText('操作完成',{exact:true}).waitFor();
    }
    assert.equal(ride.controlMode,'automatic');
    await Promise.all([
      page.waitForRequest(r => r.url().endsWith('/command') && r.postDataJSON()?.type === 'taxi.geometry'),
      page.locator('#trackRoute').check()
    ]);
    await page.locator('#mapStatus').filter({hasText:'已走 40 / 90 格'}).waitFor();
    await page.selectOption('#mapLeg','ON_TRIP');assert.equal(await page.locator('#mapLeg').inputValue(),'ON_TRIP');
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.deepEqual(errors,[]);
    assert.ok(['taxi.centers.put','taxi.quote','taxi.create','taxi.logs','taxi.passengers','taxi.control','taxi.geometry'].every(t=>requests.includes(t)));
    console.log('Chrome center/quote/order/passengers/timeline/speed/emergency/geometry/mobile checks passed');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
